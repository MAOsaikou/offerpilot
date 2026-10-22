package com.offerpilot.job.application;

import java.util.*;
import java.util.regex.Pattern;

/** Literal Latin skills and Chinese bigrams; no generated synonyms or inferred candidate facts. */
final class JobKeywords {
    private JobKeywords() {}
    static Set<String> tokens(String text) {
        var result=new LinkedHashSet<String>();
        var matcher=Pattern.compile("[a-zA-Z][a-zA-Z0-9+#.]{1,30}|[\\p{IsHan}]{2,}").matcher(text.toLowerCase(Locale.ROOT));
        while(matcher.find()) {
            String token=matcher.group();
            if(Character.UnicodeScript.of(token.codePointAt(0))==Character.UnicodeScript.HAN && token.length()>2)
                for(int i=0;i<token.length()-1;i++) result.add(token.substring(i,i+2));
            else result.add(token);
            if(result.size()>=200) break;
        }
        return result;
    }
}
