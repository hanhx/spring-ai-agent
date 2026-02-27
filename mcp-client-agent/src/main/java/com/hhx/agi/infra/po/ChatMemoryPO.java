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
    
    private String conversationId;
    
    private String messageType;
    
    private String content;
    
    private LocalDateTime createdAt;
}
