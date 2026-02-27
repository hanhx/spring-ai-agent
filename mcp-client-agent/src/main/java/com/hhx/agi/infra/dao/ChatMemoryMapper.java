package com.hhx.agi.infra.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hhx.agi.infra.po.ChatMemoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMemoryMapper extends BaseMapper<ChatMemoryPO> {

    List<ChatMemoryPO> selectByConversationId(@Param("conversationId") String conversationId);

    int deleteByConversationId(@Param("conversationId") String conversationId);

    List<String> selectAllConversationIds();
}
