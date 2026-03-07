package com.hhx.agi.infra.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.hhx.agi.infra.handler.VectorTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "skill_registry", autoResultMap = true)
public class SkillRegistryPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private String allowedTools;

    private String promptBody;

    private Boolean enabled;

    private Integer priority;

    /**
     * 向量字段：存储 name + description 的 embedding
     * MySQL 8.4 VECTOR(1024) 类型，使用自定义 TypeHandler 处理
     */
    @TableField(typeHandler = VectorTypeHandler.class)
    private float[] embedding;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}