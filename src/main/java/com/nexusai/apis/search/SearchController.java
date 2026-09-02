package com.nexusai.apis.search;

import com.nexusai.model.search.dto.SearchItemDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 搜索端点（v1 stub：始终返回空数组） */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    @GetMapping
    public List<SearchItemDto> search(@RequestParam("q") String q) {
        // v1 stub：等 Phase 5 接入会话/消息/文件/技能全文索引后实现
        return List.of();
    }
}
