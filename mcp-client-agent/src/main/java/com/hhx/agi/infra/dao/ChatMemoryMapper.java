package com.hhx.agi.infra.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hhx.agi.infra.po.ChatMemoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMemoryMapper extends BaseMapper<ChatMemoryPO> {

    List<ChatMemoryPO> selectByUserIdAndConversationId(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId);

    int deleteByUserIdAndConversationId(
            @Param("userId") String userId,
            @Param("conversationId") String conversationId);

    List<String> selectAllConversationIdsByUserId(@Param("userId") String userId);
}
