package com.offerpilot.common.text;

import java.util.*;
import java.util.regex.Pattern;

/** Shared numeric evidence rule used by resume optimization and interview coaching. */
public final class NumericProvenance {
    private NumericProvenance() {}
    private static final String HAN = "[零〇一二两三四五六七八九十百千万亿]+";
    private static final Pattern NUMBERS = Pattern.compile("[0-9０-９]+(?:[.,．][0-9０-９]+)*(?:[%％])?|百分之"+HAN
        +"|"+HAN+"(?:个|项|人|名|次|倍|套|年|月|日|天|周|秒|毫秒)");
    public static Set<String> numbers(String text) {
        Set<String> values = new HashSet<>(); var matcher = NUMBERS.matcher(text);
        while (matcher.find()) values.add(matcher.group());
        return values;
    }
    public static String stripUnsupported(String text, String evidence) {
        var known = numbers(evidence);
        return NUMBERS.matcher(text).replaceAll(match -> known.contains(match.group()) ? java.util.regex.Matcher.quoteReplacement(match.group()) : "【补充你的具体数据】");
    }
}
