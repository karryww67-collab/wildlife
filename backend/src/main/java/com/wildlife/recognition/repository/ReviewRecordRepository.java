package com.wildlife.recognition.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wildlife.recognition.entity.ReviewRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReviewRecordRepository extends BaseMapper<ReviewRecord> {
}
