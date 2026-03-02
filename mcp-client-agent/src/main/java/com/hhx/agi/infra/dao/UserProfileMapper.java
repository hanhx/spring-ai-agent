package com.hhx.agi.infra.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hhx.agi.infra.po.UserProfilePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfilePO> {

    List<UserProfilePO> selectByUserId(@Param("userId") String userId);

    int upsert(@Param("po") UserProfilePO po);
}