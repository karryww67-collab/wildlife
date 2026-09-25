package com.wildlife.recognition.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wildlife.recognition.entity.DetectionResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DetectionResultRepository extends BaseMapper<DetectionResult> {

    /**
     * 真正的批量插入：一条 SQL 带多行 VALUES。
     *
     * <pre>
     * INSERT INTO detection_result (image_id, model_id, ..., create_time)
     * VALUES (?, ?, ..., ?), (?, ?, ..., ?), ...
     * </pre>
     *
     * 与 BaseMapper#{@code insert} 的区别：{@code insert} 每行一条 SQL，
     * 本方法把整个 list 拼成一条 SQL，N 行结果 = 1 次数据库往返。
     *
     * 语句由 MyBatis-Plus 官方的
     * {@code com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn}
     * 注入（见 {@code MybatisPlusConfig#sqlInjector()}），
     * 因此方法名必须与它写死的 methodName 完全一致，否则启动即报
     * Invalid bound statement。列清单由实体注解推导，不需要手写列名。
     *
     * @param list 待插入的结果行（非空集合，且每行 imageId 已填好）
     * @return 实际插入的行数
     */
    int insertBatchSomeColumn(@Param("list") List<DetectionResult> list);
}
