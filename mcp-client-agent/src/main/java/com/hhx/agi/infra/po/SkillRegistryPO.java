package com.hhx.agi.infra.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("skill_registry")
public class SkillRegistryPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private String allowedTools;

    private String promptBody;

    private Boolean enabled;

    private Integer priority;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}