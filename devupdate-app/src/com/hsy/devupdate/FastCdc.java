package com.hsy.devupdate;

/**
 * FastCDC 内容定义分块（客户端侧）。
 *
 * 必须与 Unity 工程 <c>D:\UnityWork2026\MRShenYang\Assets\Scripts\JS\Update\FastCdc.cs</c>
 * 完全一致：分块参数、gear 表、mask、XxHash64 任何一处不同，切块边界就会错开，
 * 差分命中率直接归零（退化成下整包）。
 */
public final class FastCdc {

    /** 最小块 16KB */
    public static final int MIN_SIZE = 16 * 1024;
    /** 平均块 64KB */
    public static final int AVG_SIZE = 64 * 1024;
    /** 最大块 128KB */
    public static final int MAX_SIZE = 128 * 1024;

    // 平均 64KB ⇒ 需 16 位；归一化分两段，前段严格（块偏长）、后段宽松（块偏短）
    private static final int MASK_S_BITS = 18;
    private static final int MASK_L_BITS = 14;
    private static final long MASK_S = (1L << MASK_S_BITS) - 1;
    private static final long MASK_L = (1L << MASK_L_BITS) - 1;

    private static final long[] GEAR = buildGear();

    private FastCdc() {}

    /** 确定性生成 256 个 gear 常量（splitmix64，seed 固定）。 */
    private static long[] buildGear() {
        long[] g = new long[256];
        long x = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 256; i++) {
            x += 0x9E3779B97F4A7C15L;
            long z = x;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            g[i] = z ^ (z >>> 31);
        }
        return g;
    }

    /** 从 start 开始算下一个块的边界，返回块长。 */
    public static int nextCut(byte[] d, int start, int total) {
        int remaining = total - start;
        if (remaining <= MIN_SIZE) {
            return remaining;
        }
        int maxLen = Math.min(MAX_SIZE, remaining);
        if (maxLen <= MIN_SIZE) {
            return remaining;
        }

        // 归一化：前 1/3 用严格 mask（块偏长），后 2/3 用宽松 mask（块偏短）
        int center = MIN_SIZE + (maxLen - MIN_SIZE) / 3;
        int end1 = start + center;
        int end = start + maxLen;

        long fp = 0;
        int j = start + MIN_SIZE;
        for (; j < end1; j++) {
            fp = (fp << 1) + GEAR[d[j] & 0xFF];
            if ((fp & MASK_S) == 0) {
                return j - start + 1;
            }
        }
        for (; j < end; j++) {
            fp = (fp << 1) + GEAR[d[j] & 0xFF];
            if ((fp & MASK_L) == 0) {
                return j - start + 1;
            }
        }
        return maxLen;
    }

    /** 切块回调：(绝对流偏移, 缓冲, 块在缓冲中的起点, 块长)。块数据只在回调期间有效。 */
    public interface BlockSink {
        void onBlock(long streamPos, byte[] data, int off, int len) throws Exception;
    }

    /**
     * 流式分块器。内存上限约 2×MAX_SIZE，可安全处理 500MB 的 APK。
     */
    public static final class Chunker {
        private byte[] buf = new byte[MAX_SIZE * 2];
        private int len;
        private long streamPos;

        public void feed(byte[] data, int offset, int count, BlockSink sink) throws Exception {
            if (count <= 0) return;
            append(data, offset, count);
            drain(sink, false);
        }

        public void flush(BlockSink sink) throws Exception {
            drain(sink, true);
            if (len > 0) {
                sink.onBlock(streamPos, buf, 0, len);
                streamPos += len;
                len = 0;
            }
        }

        public void reset() {
            len = 0;
            streamPos = 0;
        }

        private void append(byte[] data, int offset, int count) {
            ensureCapacity(len + count);
            System.arraycopy(data, offset, buf, len, count);
            len += count;
        }

        private void ensureCapacity(int need) {
            if (buf.length >= need) return;
            int newSize = buf.length;
            while (newSize < need) newSize *= 2;
            byte[] nb = new byte[newSize];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }

        private void drain(BlockSink sink, boolean flush) throws Exception {
            while (true) {
                int remaining = len;
                if (remaining <= 0) return;

                // 不足 MAX_SIZE 且不是收尾 ⇒ 等更多数据，避免"短窗口"切出与整段不一致的边界
                if (!flush && remaining < MAX_SIZE) return;

                int cut = nextCut(buf, 0, len);
                sink.onBlock(streamPos, buf, 0, cut);

                len -= cut;
                if (len > 0) {
                    System.arraycopy(buf, cut, buf, 0, len);
                }
                streamPos += cut;

                if (flush && len <= 0) return;
            }
        }
    }
}
