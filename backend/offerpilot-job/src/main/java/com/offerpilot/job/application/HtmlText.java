package com.offerpilot.job.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal HTML-to-text for job pages: drop scripts/styles/navigation chrome, keep block line breaks. */
final class HtmlText {
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern OG_TITLE = Pattern.compile("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']");

    private HtmlText() {}

    static String title(String html) {
        Matcher og = OG_TITLE.matcher(html);
        if (og.find()) return entities(og.group(1)).strip();
        Matcher m = TITLE.matcher(html);
        return m.find() ? entities(m.group(1)).replaceAll("\\s+", " ").strip() : "";
    }

    static String text(String html) {
        String s = html.replaceAll("(?is)<(script|style|noscript|svg|template|head|nav|footer|header)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6]|tr|section|article|ul|ol|dd|dt)>", "\n")
                .replaceAll("(?i)<li[^>]*>", "\n• ")
                .replaceAll("(?s)<[^>]*>", " ");
        s = entities(s);
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.replaceAll("[\\t\\x0B\\f\\r \\u00a0\\u3000]+", " ").strip();
            if (!t.isEmpty() && !t.equals("•")) out.append(t).append('\n');
        }
        return out.toString().strip();
    }

    private static String entities(String s) {
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }
}
