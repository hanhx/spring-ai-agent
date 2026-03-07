package com.hhx.agi.infra.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hhx.agi.infra.po.UserPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserPO> {
}