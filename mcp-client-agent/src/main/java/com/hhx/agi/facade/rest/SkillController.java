package com.hhx.agi.facade.rest;

import com.hhx.agi.application.service.SkillService;
import com.hhx.agi.infra.po.SkillRegistryPO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 管理 REST API
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 获取所有 Skills
     */
    @GetMapping
    public ResponseEntity<List<SkillRegistryPO>> listAll() {
        return ResponseEntity.ok(skillService.listAll());
    }

    /**
     * 获取启用的 Skills
     */
    @GetMapping("/enabled")
    public ResponseEntity<List<SkillRegistryPO>> listEnabled() {
        return ResponseEntity.ok(skillService.listEnabled());
    }

    /**
     * 根据 ID 获取 Skill
     */
    @GetMapping("/{id}")
    public ResponseEntity<SkillRegistryPO> getById(@PathVariable Long id) {
        SkillRegistryPO skill = skillService.getById(id);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(skill);
    }

    /**
     * 创建 Skill
     */
    @PostMapping
    public ResponseEntity<SkillRegistryPO> create(@RequestBody SkillRegistryPO skill) {
        try {
            SkillRegistryPO created = skillService.create(skill);
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 更新 Skill
     */
    @PutMapping("/{id}")
    public ResponseEntity<SkillRegistryPO> update(@PathVariable Long id, @RequestBody SkillRegistryPO skill) {
        try {
            SkillRegistryPO updated = skillService.update(id, skill);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 删除 Skill
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        skillService.delete(id);
        return ResponseEntity.ok().build();
    }

    /**
     * 切换 Skill 启用状态
     */
    @PostMapping("/{id}/toggle")
    public ResponseEntity<SkillRegistryPO> toggleEnabled(@PathVariable Long id) {
        try {
            SkillRegistryPO skill = skillService.toggleEnabled(id);
            return ResponseEntity.ok(skill);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 重新生成所有向量
     */
    @PostMapping("/regenerate-vectors")
    public ResponseEntity<Map<String, String>> regenerateVectors() {
        try {
            skillService.regenerateAllVectors();
            return ResponseEntity.ok(Map.of("status", "success", "message", "向量重新生成成功"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * 测试向量匹配
     */
    @PostMapping("/test-match")
    public ResponseEntity<Map<String, Object>> testMatch(@RequestBody Map<String, Object> request) {
        String query = (String) request.get("query");
        Integer topK = (Integer) request.getOrDefault("topK", 5);

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "query is required"));
        }

        List<SkillRegistryPO> results = skillService.testMatch(query, topK);

        Map<String, Object> response = new HashMap<>();
        response.put("query", query);
        response.put("topK", topK);
        response.put("results", results.stream().map(po -> {
            Map<String, Object> item = new HashMap<>();
            item.put("name", po.getName());
            item.put("description", po.getDescription());
            item.put("enabled", po.getEnabled());
            item.put("priority", po.getPriority());
            return item;
        }).toList());

        return ResponseEntity.ok(response);
    }
}