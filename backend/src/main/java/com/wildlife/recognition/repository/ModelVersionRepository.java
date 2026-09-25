package com.wildlife.recognition.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wildlife.recognition.entity.ModelVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelVersionRepository extends BaseMapper<ModelVersion> {
}
