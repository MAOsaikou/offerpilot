package com.offerpilot.knowledge.application;

import com.offerpilot.common.document.DocumentTextReader;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.resume.application.ResumeObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaffKnowledgeDocumentsTest {
    @Test void rejectsInvalidOriginalsBeforeReservingStorageOrDatabaseWork() {
        var jdbc=mock(JdbcTemplate.class); var storage=mock(ResumeObjectStore.class);
        var documents=new StaffKnowledgeDocuments(jdbc,mock(TransactionTemplate.class),storage,new DocumentTextReader());
        for (String name : java.util.List.of("bad.pdf","bad.docx",""))
            assertThatThrownBy(() -> documents.upload("staff",name,"application/octet-stream","invalid".getBytes())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> documents.upload("staff","bad.txt","text/plain",new byte[]{(byte)0xff})).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> documents.list(0,20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc,storage);
    }
    @Test void unicodeChunksPreserveAllTextAndOverlapWithoutSplittingSurrogates() {
        String text="😀中Redis".repeat(600);
        var chunks=StaffKnowledgeIndexJob.chunks(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        StringBuilder rebuilt=new StringBuilder(chunks.get(0));
        for (int i=1;i<chunks.size();i++) {
            assertThat(chunks.get(i-1).substring(chunks.get(i-1).offsetByCodePoints(0,1650)))
                    .isEqualTo(chunks.get(i).substring(0,chunks.get(i).offsetByCodePoints(0,150)));
            rebuilt.append(chunks.get(i).substring(chunks.get(i).offsetByCodePoints(0,150)));
        }
        assertThat(rebuilt.toString()).isEqualTo(text);
    }
}
