package com.benjaminwan.chinesettsmodule.utils;

public final class NumberToCH {
    /** 占位：把日期字符串原样返回，后续按需实现真正的转中文读法 */
    public static String dateToCH(String s) {
        return s == null ? "" : s;
    }
    /** 占位：把数字转成字符串，后续按需实现真正的中文数词 */
    public static String numberToCH(long n) {
        return Long.toString(n);
    }
}
