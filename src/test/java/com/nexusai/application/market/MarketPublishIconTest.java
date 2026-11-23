package com.nexusai.application.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [2026-11-23 市场图标] 上架清单 icon 字段的写出规则。
 *
 * <p><b>WHY</b>：重发版时请求不带 icon 是常态（管理页只改描述/版本）。若「空值」也写进清单，
 * 会把上一版设置好的图标清掉；因此判据必须是「非空才写」。显式 URL 则 trim 后落盘。
 */
class MarketPublishIconTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("非空 URL 写入（trim 空白）")
    void writesTrimmedUrl() throws Exception {
        ObjectNode entry = JSON.createObjectNode();
        writeIcon(entry, "  http://market/icons/skill.svg  ");
        assertThat(entry.path("icon").asText()).isEqualTo("http://market/icons/skill.svg");
    }

    @Test
    @DisplayName("null / 空白不写，也不覆盖既有 icon")
    void blankDoesNotOverwrite() throws Exception {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("icon", "http://market/icons/old.svg");

        writeIcon(entry, null);
        assertThat(entry.path("icon").asText()).isEqualTo("http://market/icons/old.svg");

        writeIcon(entry, "   ");
        assertThat(entry.path("icon").asText()).isEqualTo("http://market/icons/old.svg");
    }

    private static void writeIcon(ObjectNode entry, String icon) throws Exception {
        Method m = MarketPublishService.class.getDeclaredMethod("writeIcon", ObjectNode.class, String.class);
        m.setAccessible(true);
        m.invoke(null, entry, icon);
    }
}
