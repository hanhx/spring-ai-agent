package com.hhx.agi.infra.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_profile")
public class UserProfilePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private String profileKey;

    private String profileValue;

    private String sourceMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}