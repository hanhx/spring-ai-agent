package com.hhx.agi.infra.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pending_intents")
public class PendingIntentPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    private String skillName;

    private String subTask;

    private LocalDateTime createdAt;
}