package com.offerpilot.privacy;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
class UserDataInventoryTest {
    @Test void flywayOwnerTablesAreAllInTheDeletionInventory() throws Exception {
        var found=new TreeSet<String>();
        Pattern create=Pattern.compile("(?is)CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*?)\\n\\);");
        Pattern alter=Pattern.compile("(?is)ALTER\\s+TABLE\\s+(\\w+)\\s+ADD(?:\\s+COLUMN)?\\s+(?:user_id|owner_id)\\b");
        try(var files=Files.list(Path.of("src/main/resources/db/migration"))) {
            for(var path:files.toList()) {
                String name=path.getFileName().toString();
                if(!name.matches("V[0-9]+__.*")) continue;
                String sql=Files.readString(path).replaceAll("(?m)--.*$", "");
                var c=create.matcher(sql);
                while(c.find()) if(Pattern.compile("(?i)\\b(?:user_id|owner_id)\\b").matcher(c.group(2)).find()) found.add(c.group(1));
                var a=alter.matcher(sql);while(a.find()) found.add(a.group(1));
            }
        }
        assertThat(found).contains("llm_usage_logs","jobs","notifications");
        assertThat(found).containsExactlyInAnyOrderElementsOf(UserDataInventory.CASCADE_TABLES);
    }
}
