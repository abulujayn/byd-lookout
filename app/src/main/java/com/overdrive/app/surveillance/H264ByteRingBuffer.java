package com.overdrive.app.surveillance;

import android.media.MediaCodec;

import com.overdrive.app.logging.DaemonLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H264ByteRingBuffer — SOTA pre-record ring with constant memory budget.
 *
 * <p>Replaces a legacy slot-pool design (every packet got a 1 MB slot
 * whether it was a 30 KB P-frame or a 1 MB I-frame, wasting ~80% of the
 * budget on padding) with a single contiguous direct {@link ByteBuffer}
 * arena + a parallel primitive-array index of packet headers. Bytes pack
 * tightly; only headers (~96 KB total) live on the Java heap.
 *
 * <h3>Why this is a win for our consumer</h3>
 * <ul>
 *   <li><b>Density:</b> 64 MB budget at MAX H.265/30fps holds ~50 s of
 *       footage. The slot-pool buffer holds ~5 s at the same budget
 *       and OOMs the daemon if you ask for more.</li>
 *   <li><b>Bitrate-agnostic:</b> the ring doesn't care about
 *       per-packet ceilings. Quality changes never recreate the buffer.
 *       Eliminates the 4-axis (duration/fps/bitrate/codec) reuse logic
 *       in the consumer.</li>
 *   <li><b>Boot:</b> one {@code allocateDirect(BUDGET)} (~30-80 ms) instead
 *       of N×1MB direct allocations (each is a separate mmap round-trip).</li>
 *   <li><b>Flush:</b> consumer borrows {@link Cursor}s and reads bytes
 *       directly out of the ring, never deep-copying. Snapshot stability
 *       is provided by the seqlock + pin — see {@link Cursor}.</li>
 * </ul>
 *
 * <h3>Concurrency model</h3>
 * Single producer (encoder drainer thread, calls {@link #add}) and a single
 * consumer (event handler thread, calls {@link #beginFlush} / {@link Cursor}
 * methods). Producer never locks; header publication uses a seqlock
 * ({@link AtomicLong} even/odd). Consumer pins the read offset before
 * iterating; producer respects the pin by dropping incoming P-frames
 * (keyframes break the pin and force consumer retry — keyframes never drop).
 *
 * <h3>Failure modes</h3>
 * <ul>
 *   <li><b>Packet larger than budget/2:</b> dropped (defensive; never observed
 *       in practice — at MAX H.264 worst-case I-frame is ~3 MB, budget is 64 MB).</li>
 *   <li><b>Header table exhausted:</b> evict from tail until a slot is free.
 *       Header table is sized for 4096 packets — at 30fps × 30s = 900 packets,
 *       we have generous headroom.</li>
 *   <li><b>Pin held during flush, P-frame burst:</b> P-frames drop, no key
 *       loss. Pin is released as soon as the consumer finishes flushing.</li>
 *   <li><b>Pin held when a new keyframe arrives:</b> producer breaks the pin;
 *       consumer's seqlock read sees inconsistent state and retries (or in
 *       the worst case, aborts the flush — partial pre-record is acceptable).</li>
 * </ul>
 */
public class H264ByteRingBuffer {
    private static final DaemonLogger logger = DaemonLogger.getInstance("H264ByteRing");

    /** Maximum number of packet headers we can index simultaneously. Power
     * of two so we can use bitwise mask instead of modulo. At 30 fps × 30 s
     * = 900 packets, 4096 gives 4× headroom. */
    private static final int HEADER_CAPACITY = 4096;
    private static final int HEADER_MASK = HEADER_CAPACITY - 1;

    static {
        if ((HEADER_CAPACITY & HEADER_MASK) != 0) {
            throw new AssertionError("HEADER_CAPACITY must be power of two");
        }
    }

    // ── Bitstream payload ────────────────────────────────────────────────
    /** Single contiguous direct buffer. Allocated once at construction. Never
     * resized. Native size charged to Java heap on Android. */
    private final ByteBuffer payload;
    /** Capacity of {@link #payload}, cached to avoid bounds-check overhead. */
    private final int budget;
    /** Producer-thread-only view into {@link #payload} for slicing source
     * data. Reused across {@link #add} calls to avoid a per-frame
     * {@code data.duplicate()} allocation (~40 bytes/frame at 30 fps). */
    private final ByteBuffer producerPayloadView;
    /** Consumer-thread-only view into {@link #payload}. Used by
     * {@link Cursor#next} for reading bytes out without ever calling
     * {@code payload.duplicate()} concurrently with the producer's
     * {@code payload.position(...)} writes — those non-atomic field
     * mutations would otherwise tear the duplicate's mark/pos/lim
     * tuple and rarely throw {@link IllegalArgumentException} from
     * the {@code DirectByteBuffer} ctor's invariant check. Acquired
     * once at construction (quiescence) and never re-duplicated. The
     * consumer thread is single-threaded and only mutates THIS view's
     * position/limit, never {@link #payload}'s. */
    private final ByteBuffer consumerPayloadView;

    // ── Header table (parallel primitive arrays for cache locality) ──────
    /** Byte offset into {@link #payload} where each packet starts. */
    private final int[] hOffset = new int[HEADER_CAPACITY];
    /** Length in bytes of each packet's payload. */
    private final int[] hLength = new int[HEADER_CAPACITY];
    /** Presentation timestamp (microseconds) of each packet. */
    private final long[] hPts = new long[HEADER_CAPACITY];
    /** {@link MediaCodec.BufferInfo#flags} bits — most importantly
     * {@link MediaCodec#BUFFER_FLAG_KEY_FRAME}. */
    private final int[] hFlags = new int[HEADER_CAPACITY];

    // ── Cursors (monotonic; logical positions, modulo HEADER_CAPACITY for
    //    table indexing and modulo budget for byte indexing) ─────────────
    /** Index of the oldest valid header. Bumped by eviction. Read-modify
     * by producer only; consumer reads via seqlock. */
    private long headerHead;
    /** Index just past the newest valid header. Bumped by {@link #add}. */
    private long headerTail;
    /** Byte offset of the oldest stored byte. */
    private long bytesHead;
    /** Byte offset just past the newest stored byte. */
    private long bytesTail;

    /** Number of keyframes currently in the ring. Producer-only writes. */
    private int keyframeCount;
    /** Minimum keyframes the ring must retain even when over duration budget.
     * Computed from {@code (durationSec / 2) + 2} (encoder uses 2-second GOP).
     * <p>volatile because it is mutated by the control plane (setMaxDurationUs
     * called from any thread) and read by the producer thread inside add()'s
     * prune loop. The producer's read happens-before the producer's eviction
     * decision; without volatile the JMM doesn't guarantee the new value is
     * visible to a producer running on a different CPU. */
    private volatile int minKeyframes;

    /** User-configured maximum window in microseconds. Producer prunes
     * headers whose PTS spans exceed this after each {@link #add}. */
    private volatile long maxDurationUs;

    // ── Seqlock (producer-side publication) ──────────────────────────────
    /** Even = stable, odd = mid-write. Producer increments before+after
     * mutating cursors; consumer reads even-on-entry, validates same on
     * exit. Lock-free read path. */
    private final AtomicLong seq = new AtomicLong(0);

    // ── Pin (consumer-side flush guard) ──────────────────────────────────
    /** Byte offset the consumer is currently reading from. Producer's
     * eviction respects this — won't advance {@link #bytesHead} past
     * {@code pin}. {@link Long#MIN_VALUE} when no pin is active.
     * <p>Volatile so producer sees the pin as soon as the consumer
     * sets it. */
    private final AtomicLong pinOffset = new AtomicLong(Long.MIN_VALUE);

    // ── Stats (debug) ────────────────────────────────────────────────────
    private long totalAdds;
    private long totalKeyDrops;
    private long totalPDrops;
    private long totalEvictions;

    /**
     * Create a ring buffer with the given native budget and initial duration
     * window. Both can be tuned at runtime via {@link #setMaxDurationUs}.
     *
     * @param budgetBytes      Total native-heap budget (e.g. 64 MB). Allocated
     *                         once. Must be ≥ 1 MB.
     * @param initialDurationS Initial pre-record window in seconds. 1-30.
     */
    public H264ByteRingBuffer(int budgetBytes, int initialDurationS) {
        if (budgetBytes < 1024 * 1024) {
            throw new IllegalArgumentException("budgetBytes too small: " + budgetBytes);
        }
        this.budget = budgetBytes;
        this.payload = ByteBuffer.allocateDirect(budgetBytes).order(ByteOrder.nativeOrder());
        // Duplicate ONCE at construction (quiescence — no concurrent producer).
        // Each thread thereafter mutates only its own view's position/limit;
        // the underlying native pointer + capacity are immutable.
        this.producerPayloadView = this.payload.duplicate().order(ByteOrder.nativeOrder());
        this.consumerPayloadView = this.payload.duplicate().order(ByteOrder.nativeOrder());
        setInitialDuration(initialDurationS);
        logger.info("H264ByteRingBuffer ready: budget=" + (budgetBytes / 1024 / 1024)
            + "MB, duration=" + initialDurationS + "s, minKeyframes="
            + minKeyframes + ", headerCap=" + HEADER_CAPACITY);
    }

    private void setInitialDuration(int durationSeconds) {
        int clamped = Math.max(1, Math.min(30, durationSeconds));
        this.maxDurationUs = clamped * 1_000_000L;
        this.minKeyframes = (clamped / 2) + 2;
    }

    /**
     * Add an encoded packet to the ring. Called from the encoder drainer
     * thread. Must not block, must not allocate.
     *
     * <p>Behavior on contention with an in-flight flush ({@link #pinOffset}
     * set):
     * <ul>
     *   <li>P-frame, eviction would cross the pin → drop the new P-frame
     *       (transient — pin is short-lived).</li>
     *   <li>I-frame, eviction would cross the pin → break the pin
     *       (consumer's seqlock retry catches the inconsistency and aborts
     *       its flush). Keyframes never drop.</li>
     * </ul>
     *
     * @param data Source ByteBuffer (typically the encoder's output buffer).
     *             Position/limit will be modified.
     * @param info Source metadata. Only {@code offset}, {@code size},
     *             {@code presentationTimeUs}, and {@code flags} are read.
     */
    public void add(ByteBuffer data, MediaCodec.BufferInfo info) {
        final int sz = info.size;
        if (sz <= 0) return;
        if (sz > budget / 2) {
            logger.warn("Dropping pathological packet (size=" + sz + " > budget/2)");
            return;
        }
        final boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

        // 1. Make room. Evict from tail until we have `sz` free bytes AND
        //    a free header slot. Respect the pin: drop new P-frames, break
        //    pin for new I-frames.
        while (freeBytes() < sz || headerCount() >= HEADER_CAPACITY) {
            if (!evictTail(isKey)) {
                // Could not evict (pin holds AND we're a P-frame).
                if (isKey) totalKeyDrops++;
                else totalPDrops++;
                return;
            }
        }

        // 2. Write payload bytes through the producer's private view (never
        //    via `payload` directly — the consumer thread holds its own view
        //    and a concurrent ByteBuffer.duplicate() would race the
        //    payload.position(...) writes here, occasionally throwing
        //    IllegalArgumentException from the duplicate ctor's
        //    mark/pos/limit invariant check). The producer's view shares
        //    `payload`'s native bytes but has its own position/limit fields.
        final int writePos = (int) (bytesTail % budget);
        if (writePos + sz <= budget) {
            // Note: Buffer.position(int)/limit(int) return `Buffer` (covariant
            // override added in Java 9), so the chained form fails on Android's
            // older bytecode target. Split into discrete statements.
            final ByteBuffer src = data.duplicate();
            src.position(info.offset);
            src.limit(info.offset + sz);
            producerPayloadView.position(writePos);
            producerPayloadView.put(src);
        } else {
            // Wrap: split into [writePos..budget) + [0..remaining).
            final int firstChunk = budget - writePos;
            final ByteBuffer src = data.duplicate();
            src.position(info.offset);
            src.limit(info.offset + firstChunk);
            producerPayloadView.position(writePos);
            producerPayloadView.put(src);

            // Defensive: explicitly set src.position. The Java spec guarantees
            // ByteBuffer.put(ByteBuffer) advances src.position by the number
            // of bytes copied, but resetting it explicitly removes a fragile
            // dependency on that side effect for future readers of this code.
            src.position(info.offset + firstChunk);
            src.limit(info.offset + sz);
            producerPayloadView.position(0);
            producerPayloadView.put(src);
        }

        // 3. Publish header. Seqlock: pre-increment to odd, mutate, post-increment to even.
        //
        // Memory-model note (load-bearing): `lazySet` on AtomicLong is
        // `putOrderedLong` underneath — it has *release* semantics. That
        // means all plain stores BEFORE the second lazySet (the array
        // writes below) are guaranteed visible-before the seq=even value
        // becomes visible to any consumer that performs a volatile (acquire)
        // read of seq. This is what makes the seqlock work without an
        // explicit StoreStore fence between the array writes and the
        // publish. JSR-133 §17.4.5 covers the guarantee. Don't downgrade
        // either lazySet to a plain store; don't reorder array writes
        // after the second lazySet.
        seq.lazySet(seq.get() + 1);  // odd
        final int h = (int) (headerTail & HEADER_MASK);
        hOffset[h] = writePos;
        hLength[h] = sz;
        hPts[h]    = info.presentationTimeUs;
        hFlags[h]  = info.flags;
        headerTail++;
        bytesTail += sz;
        if (isKey) keyframeCount++;
        seq.lazySet(seq.get() + 1);  // even, published (release fence)

        totalAdds++;

        // 4. Duration-based pruning. Memory-budget eviction in step 1
        //    keeps RAM bounded; this enforces the user's chosen window.
        //
        // CRITICAL: this loop respects the pin via evictTail(false). At MAX
        // settings under saturated steady-state (every add at duration cap),
        // bypassing the pin here would let a flush-in-flight cursor see its
        // bytes evicted, partially aborting the flush and silently shrinking
        // the user's pre-record window from the configured 30s to as little
        // as 1-2s. By respecting the pin during duration-prune, the producer
        // pauses pruning while a flush holds the read frontier; the buffer
        // briefly exceeds maxDurationUs by the flush's duration (~150 ms),
        // then prunes back to the configured window after the cursor closes.
        // The keyframe floor (minKeyframes) still applies as a hard policy.
        while (currentDurationUs() > maxDurationUs && headerCount() > 1) {
            final int t = (int) (headerHead & HEADER_MASK);
            // Don't drop a keyframe when we're at the minimum.
            if ((hFlags[t] & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    && keyframeCount <= minKeyframes) {
                break;
            }
            // evictTail(false): pin-blocking eviction. If the pin holds, we
            // exit the loop without further pruning — the next add will
            // re-enter and try again. The maxDurationUs overshoot during
            // a flush is bounded by flush duration (~150 ms typical).
            if (!evictTail(false)) {
                break;
            }
        }
    }

    /**
     * Try to evict one packet from the tail. Returns false if blocked by
     * the pin (and `isKey` is false — keys break the pin).
     */
    private boolean evictTail(boolean isKey) {
        if (headerCount() == 0) return false;
        final long pin = pinOffset.get();
        if (pin != Long.MIN_VALUE) {
            final int t = (int) (headerHead & HEADER_MASK);
            final long tailEndsAt = bytesHead + hLength[t];
            if (tailEndsAt > pin) {
                // Eviction would cross the pin's read frontier.
                if (!isKey) return false;
                // Key-driven pin break: clear the pin so the consumer's
                // seqlock retry aborts cleanly.
                pinOffset.set(Long.MIN_VALUE);
            }
        }
        evictTailOnce();
        return true;
    }

    private void evictTailOnce() {
        seq.lazySet(seq.get() + 1);  // odd
        final int t = (int) (headerHead & HEADER_MASK);
        if ((hFlags[t] & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            keyframeCount--;
        }
        bytesHead += hLength[t];
        headerHead++;
        seq.lazySet(seq.get() + 1);  // even
        totalEvictions++;
    }

    private int headerCount() {
        return (int) (headerTail - headerHead);
    }

    private int freeBytes() {
        return budget - (int) (bytesTail - bytesHead);
    }

    private long currentDurationUs() {
        if (headerCount() < 2) return 0;
        final int first = (int) (headerHead & HEADER_MASK);
        final int last = (int) ((headerTail - 1) & HEADER_MASK);
        return hPts[last] - hPts[first];
    }

    /**
     * Empty the buffer without releasing the underlying byte arena. Cheap.
     * Called between encoder reinitializations and from {@link #release}
     * when the encoder is being torn down for restart.
     *
     * <p>Resets {@link #pinOffset} too. Without this, an orphaned cursor
     * left over from an interrupted flush (e.g., daemon shutdown mid-event,
     * encoder reinit while a flush is in flight) keeps the pin set after
     * its bytes were cleared. The next encoder boot would see a stale pin
     * pointing at a now-evicted byte offset, blocking eviction of all
     * P-frames until the next keyframe arrives ~2s later — silently
     * truncating the new pre-record window.
     */
    public synchronized void clear() {
        seq.lazySet(seq.get() + 1);  // odd
        headerHead = headerTail = 0;
        bytesHead = bytesTail = 0;
        keyframeCount = 0;
        pinOffset.set(Long.MIN_VALUE);
        seq.lazySet(seq.get() + 1);  // even
        logger.info("Buffer cleared (byte arena retained, pin released)");
    }

    /** Update the duration window. Cheap — no reallocation. */
    public void setMaxDurationUs(long newMaxUs) {
        long clamped = Math.max(1_000_000L, Math.min(30_000_000L, newMaxUs));
        this.maxDurationUs = clamped;
        // Recompute minKeyframes based on new duration so the prune-keep
        // policy adjusts.
        this.minKeyframes = (int) (clamped / 2_000_000L) + 2;
    }

    public long getMaxDurationUs() { return maxDurationUs; }

    /** @return current packet count (approximate when concurrent with add). */
    public int size() { return headerCount(); }

    /** @return current duration in seconds (approximate). */
    public double getDurationSeconds() { return currentDurationUs() / 1_000_000.0; }

    /** @return total bytes pinned by header table (approximate). */
    public int storedBytes() { return (int) (bytesTail - bytesHead); }

    public String getStats() {
        return String.format(
            "ByteRing: %d packets, %.1f sec, %d keyframes, %.1f MB stored, "
            + "adds=%d evicts=%d keyDrops=%d pDrops=%d",
            headerCount(), getDurationSeconds(), keyframeCount,
            storedBytes() / 1024.0 / 1024.0,
            totalAdds, totalEvictions, totalKeyDrops, totalPDrops);
    }

    // Structured stat accessors — exposed via /api/status so the UI can show
    // health (key drops should be zero in steady state; non-zero means the
    // pin held during a keyframe arrival, which is a SOTA-grade rare event
    // worth logging). All counters are diagnostic-only and read without
    // synchronization; values are eventually-consistent across threads.
    public long getTotalAdds()       { return totalAdds; }
    public long getTotalEvictions()  { return totalEvictions; }
    public long getTotalKeyDrops()   { return totalKeyDrops; }
    public long getTotalPDrops()     { return totalPDrops; }

    // ── Compatibility shims (called by HardwareEventRecorderGpu.init for
    //    triplet-mismatch detection in the legacy buffer path). The byte
    //    ring is bitrate- and fps-agnostic, so these return sentinels that
    //    cause the consumer's reuse logic to always reuse. ────────────────
    public int getSizedForFps() { return -1; }
    public int getSizedForBitrate() { return -1; }
    public int getPoolFreeCount() { return budget - storedBytes(); }

    // ────────────────────────────────────────────────────────────────────
    // CONSUMER API — flush
    // ────────────────────────────────────────────────────────────────────

    /**
     * Begin a flush. Pins the current read frontier so the producer won't
     * overwrite bytes the consumer is about to read. Returns a {@link Cursor}
     * positioned at the oldest keyframe in the buffer (or {@code null} if
     * the buffer has no keyframes — flush would be undecodable, skip).
     *
     * <p>The cursor is single-use. Call {@link Cursor#next} until it returns
     * {@code false}, then {@link Cursor#close} (which releases the pin).
     *
     * <p>If the producer breaks the pin while the cursor is in-flight,
     * {@link Cursor#next} returns {@code false} and {@link Cursor#aborted}
     * returns {@code true}. The partial flush captured up to that point is
     * still valid for muxing — the seqlock validation guarantees we only
     * yielded packets whose bytes were not overwritten.
     */
    public Cursor beginFlush() {
        // Whole-walk seqlock retry: snapshot cursors, walk hFlags/hLength to
        // find the first keyframe and compute the pin offset, then revalidate
        // seq. If the producer evicted any header during the walk, retry from
        // scratch — without this, a stale firstKey/pinAt could point at bytes
        // the producer already overwrote, making the consumer read garbage
        // bitstream from the new packet that landed in the same slot.
        //
        // The walk is read-only, so retry is cheap. In practice the producer
        // only mutates seq during add() (~1 µs every 33 ms at 30 fps), so the
        // retry should happen approximately never under non-pathological load.
        for (int attempt = 0; attempt < 8; attempt++) {
            // Pre-init locals: the `continue` on odd-seq inside the do/while
            // skips the assignments, so the compiler's definite-assignment
            // check fails on the post-loop usage. The values are unused on
            // the continue path (loop reruns), but Java needs them assigned.
            long s1, s2 = 0L;
            long localHead = 0L, localTail = 0L, localBytesHead = 0L;
            // 1. Snapshot cursors under seqlock.
            do {
                s1 = seq.get();
                if ((s1 & 1L) != 0) { Thread.yield(); continue; }
                localHead = headerHead;
                localTail = headerTail;
                localBytesHead = bytesHead;
                s2 = seq.get();
            } while (s1 != s2);

            // 2. Walk for first keyframe.
            long firstKey = -1;
            for (long i = localHead; i < localTail; i++) {
                int idx = (int) (i & HEADER_MASK);
                if ((hFlags[idx] & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    firstKey = i;
                    break;
                }
            }

            // 3. Validate the walk against the seqlock. If a producer write
            //    landed during steps 1-2, the read may be torn — retry.
            long sAfter = seq.get();
            if (sAfter != s2) {
                continue;
            }

            if (firstKey < 0) {
                logger.warn("beginFlush: no keyframe in buffer (count=" + (localTail - localHead) + ") — skipping flush");
                return null;
            }

            // 4. Sum lengths up to firstKey under seqlock revalidation.
            long pinAt = localBytesHead;
            for (long i = localHead; i < firstKey; i++) {
                pinAt += hLength[(int) (i & HEADER_MASK)];
            }
            long sFinal = seq.get();
            if (sFinal != s2) {
                continue;
            }

            // 5. Commit pin and return cursor.
            pinOffset.set(pinAt);
            return new Cursor(firstKey, localTail, pinAt);
        }
        logger.warn("beginFlush: producer churn defeated 8 retries — skipping flush");
        return null;
    }

    /**
     * Iterator over a snapshotted flush range. Single-threaded (consumer-side).
     * Validates each packet against the seqlock before returning it.
     */
    public final class Cursor {
        private final long endHeader;
        private long curHeader;
        private final long pinReadFloor;
        private boolean aborted;
        private boolean closed;

        Cursor(long startHeader, long endHeader, long pinReadFloor) {
            this.curHeader = startHeader;
            this.endHeader = endHeader;
            this.pinReadFloor = pinReadFloor;
        }

        /**
         * Advance to the next packet. Returns false when no more packets
         * are available (either end-of-snapshot or the pin was broken by
         * a concurrent keyframe).
         *
         * <p>On success, fills the supplied {@code outInfo} with metadata
         * and copies the packet's bytes into {@code outDst} starting at
         * {@code outDst.position()}. {@code outDst} must have at least
         * {@link #peekSize} bytes remaining.
         *
         * @return true if a packet was emitted; false if cursor is exhausted
         *         or aborted.
         */
        public boolean next(ByteBuffer outDst, MediaCodec.BufferInfo outInfo) {
            if (closed || aborted) return false;
            if (curHeader >= endHeader) return false;

            // Validate against current pin — if producer broke our pin
            // (keyframe arrived, evicted bytes we needed), abort.
            if (pinOffset.get() == Long.MIN_VALUE) {
                aborted = true;
                return false;
            }

            // Seqlock-validated read of the header at curHeader.
            // Pre-init: continue-on-odd skips the assignments, so the
            // compiler's definite-assignment check fails without defaults.
            int idx = (int) (curHeader & HEADER_MASK);
            long s1, s2 = 0L;
            int off = 0, len = 0, flags = 0;
            long pts = 0L;
            do {
                s1 = seq.get();
                if ((s1 & 1L) != 0) {
                    Thread.yield();
                    continue;
                }
                off = hOffset[idx];
                len = hLength[idx];
                pts = hPts[idx];
                flags = hFlags[idx];
                s2 = seq.get();
            } while (s1 != s2);

            // Cross-check: if the producer evicted past curHeader during
            // our seqlock retry, the slot may now belong to a newer packet
            // with the same header index. Detect via headerHead bound.
            // Producer never evicts past the pin when consumer is alive,
            // BUT a key-driven pin break may have raced us. Treat as abort.
            if (curHeader < headerHead) {
                aborted = true;
                return false;
            }

            // Copy bytes out, handling wraparound. outDst.position() advances
            // by `len` after the puts; the consumer rewinds before muxer write.
            //
            // Use the cached consumer view rather than payload.duplicate() —
            // this avoids the producer/consumer race on payload.position()
            // that could throw IllegalArgumentException from the duplicate
            // ctor's mark/pos/limit invariant check. The consumer thread
            // is the only writer of consumerPayloadView's position/limit,
            // so we can mutate them freely here without locking.
            //
            // CRITICAL: reset limit to capacity (budget) BEFORE setting
            // position. The view persists across flushes — last call left
            // it at limit=N (some byte offset from a prior packet). If the
            // new `off` is greater than that stale limit, position(off)
            // throws IllegalArgumentException ("Bad position off/limit"),
            // which kills the drainer's flush loop mid-burst and silently
            // drops every remaining pre-record packet. Always extend limit
            // to capacity first, then narrow it down. Equivalent to a
            // Buffer.clear() but spelled explicitly so future readers
            // understand the load-bearing ordering.
            int dstStart = outDst.position();
            if (off + len <= budget) {
                consumerPayloadView.limit(budget);
                consumerPayloadView.position(off);
                consumerPayloadView.limit(off + len);
                outDst.put(consumerPayloadView);
            } else {
                int firstChunk = budget - off;
                consumerPayloadView.limit(budget);
                consumerPayloadView.position(off);
                outDst.put(consumerPayloadView);

                consumerPayloadView.position(0);
                consumerPayloadView.limit(len - firstChunk);
                outDst.put(consumerPayloadView);
            }

            // Post-copy revalidation. The seqlock above only proved the *header*
            // fields (off, len, pts, flags) were stable at read time. Between
            // the header read and the byte copy that just finished, a producer
            // could have evicted past `off` (key-driven pin break) and
            // overwritten those exact bytes with a newer packet's payload.
            // Result: a flushed packet with a valid PTS and corrupt bitstream
            // — muxer accepts it (it's just bytes), player chokes mid-pre-record
            // with no error log. The fix: re-check the pin AND seq after the
            // copy. If either changed, the bytes we just wrote are untrusted —
            // rewind outDst and abort the cursor. The partial flush up to here
            // is still valid (those bytes were validated when their packets
            // were emitted; only THIS packet's bytes are suspect).
            if (pinOffset.get() == Long.MIN_VALUE) {
                outDst.position(dstStart);
                aborted = true;
                return false;
            }
            // Tighter check: verify the slot's header didn't change while we
            // copied. If hOffset/hLength at idx still match what we read, the
            // producer didn't reuse this slot — the bytes are good. (We can't
            // re-read seq because the producer's add() advances seq for every
            // packet whether or not it touched our slot.)
            long sFinal = seq.get();
            if ((sFinal & 1L) == 0 && (hOffset[idx] != off || hLength[idx] != len)) {
                outDst.position(dstStart);
                aborted = true;
                return false;
            }

            // Populate metadata. outInfo.offset is the pre-put dst position;
            // most consumers rewind to 0 anyway (MuxerPacket.rewindForWrite).
            outInfo.set(dstStart, len, pts, flags);
            curHeader++;
            return true;
        }

        /** Bytes the next call to {@link #next} will write. -1 if exhausted. */
        public int peekSize() {
            if (closed || aborted || curHeader >= endHeader) return -1;
            return hLength[(int) (curHeader & HEADER_MASK)];
        }

        /** Total packets remaining in the cursor's snapshot range. */
        public int remaining() {
            return (int) Math.max(0, endHeader - curHeader);
        }

        /** True if the cursor was aborted by a concurrent pin break. */
        public boolean aborted() { return aborted; }

        /** Release the pin. Safe to call multiple times. */
        public void close() {
            if (closed) return;
            closed = true;
            // Only clear the pin if it's still ours (a producer key-break
            // would have already set it to MIN_VALUE — don't stomp).
            pinOffset.compareAndSet(pinReadFloor, Long.MIN_VALUE);
        }
    }

    /**
     * Compute the total byte count the cursor would emit (sum of remaining
     * packet sizes). Useful for the consumer to log expected flush size.
     * Approximate when concurrent with add.
     */
    public int peekFlushBytes() {
        // Pre-init: see beginFlush rationale.
        long s1, s2 = 0L;
        long head = 0L, tail = 0L;
        do {
            s1 = seq.get();
            if ((s1 & 1L) != 0) { Thread.yield(); continue; }
            head = headerHead;
            tail = headerTail;
            s2 = seq.get();
        } while (s1 != s2);

        int total = 0;
        boolean foundKey = false;
        for (long i = head; i < tail; i++) {
            int idx = (int) (i & HEADER_MASK);
            if (!foundKey) {
                if ((hFlags[idx] & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    foundKey = true;
                } else {
                    continue;
                }
            }
            total += hLength[idx];
        }
        return total;
    }
}
