package com.hhx.agi.infra.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_memory")
public class ChatMemoryPO {
    
    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId; // 用户 ID，支持多用户

    private String conversationId;
    
    private String messageType;
    
    private String content;
    
    private LocalDateTime createdAt;
}
