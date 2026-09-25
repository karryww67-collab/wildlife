package com.wildlife.recognition.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wildlife.recognition.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserRepository extends BaseMapper<User> {
}
