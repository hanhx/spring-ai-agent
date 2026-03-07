package com.hhx.agi.infra.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hhx.agi.infra.po.SkillRegistryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SkillRegistryMapper extends BaseMapper<SkillRegistryPO> {

    List<SkillRegistryPO> selectEnabledOrderByPriority(@Param("enabled") Boolean enabled);

    /**
     * 查询所有启用的且有向量的 skills
     */
    List<SkillRegistryPO> selectEnabledWithVector(@Param("enabled") Boolean enabled);
}