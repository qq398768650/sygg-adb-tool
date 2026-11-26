package com.hsy.devupdate;

/**
 * XxHash64（seed 固定为 0）—— 给每个块算指纹。
 * 必须与 Unity 工程 <c>FastCdc.cs</c> 里的 XxHash64 实现一致，改实现会让所有 patch 失效。
 */
public final class XxHash64 {

    // 与 C# ulong 常量等价。用十六进制字面量：负数形式容易抄错（P1 曾写成 …353131，差 6）
    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private XxHash64() {}

    /** 计算 [offset, offset+length) 的 XxHash64。 */
    public static long compute(byte[] data, int offset, int length) {
        int end = offset + length;
        long h;
        int i = offset;

        if (length >= 32) {
            long v1 = P1 + P2;
            long v2 = P2;
            long v3 = 0;
            long v4 = 0L - P1;

            int limit = end - 32;
            do {
                v1 = round(v1, readU64(data, i)); i += 8;
                v2 = round(v2, readU64(data, i)); i += 8;
                v3 = round(v3, readU64(data, i)); i += 8;
                v4 = round(v4, readU64(data, i)); i += 8;
            } while (i <= limit);

            h = rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18);
            h = mergeRound(h, v1);
            h = mergeRound(h, v2);
            h = mergeRound(h, v3);
            h = mergeRound(h, v4);
        } else {
            h = P5;
        }

        h += (long) length;

        while (i + 8 <= end) {
            long k1 = round(0, readU64(data, i));
            h ^= k1;
            h = rotl(h, 27) * P1 + P4;
            i += 8;
        }

        if (i + 4 <= end) {
            h ^= readU32(data, i) * P1;
            h = rotl(h, 23) * P2 + P3;
            i += 4;
        }

        while (i < end) {
            h ^= (data[i] & 0xFF) * P5;
            h = rotl(h, 11) * P1;
            i++;
        }

        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    private static long readU64(byte[] d, int i) {
        return (d[i] & 0xFFL)
                | ((d[i + 1] & 0xFFL) << 8)
                | ((d[i + 2] & 0xFFL) << 16)
                | ((d[i + 3] & 0xFFL) << 24)
                | ((d[i + 4] & 0xFFL) << 32)
                | ((d[i + 5] & 0xFFL) << 40)
                | ((d[i + 6] & 0xFFL) << 48)
                | ((d[i + 7] & 0xFFL) << 56);
    }

    private static long readU32(byte[] d, int i) {
        return (d[i] & 0xFFL)
                | ((d[i + 1] & 0xFFL) << 8)
                | ((d[i + 2] & 0xFFL) << 16)
                | ((d[i + 3] & 0xFFL) << 24);
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = rotl(acc, 31);
        acc *= P1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0, val);
        acc ^= val;
        return acc * P1 + P4;
    }

    private static long rotl(long x, int r) {
        return (x << r) | (x >>> (64 - r));
    }
}
