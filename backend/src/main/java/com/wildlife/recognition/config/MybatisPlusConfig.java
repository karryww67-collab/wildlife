package com.wildlife.recognition.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis-Plus 全局配置。
 *
 * 目前最重要的是开启真正的数据库分页：
 *
 * 原来：
 *   MySQL -> 查询全部 -> Java 内存 -> subList()
 *
 * 现在：
 *   MySQL -> LIMIT/OFFSET -> 只返回当前页
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 分页插件。
     *
     * maxLimit = 200：
     * 单次接口最多返回 200 条，避免前端一次请求几万条数据。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination =
                new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大数量
        pagination.setMaxLimit(200L);
        // page 超过总页数时，不自动跳回第一页
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 注入 {@code insertBatchSomeColumn}：一条 INSERT 带多行 VALUES 的真批量插入。
     *
     * 为什么需要它：{@code RedisSubscriberService} 每张图要回写 N 条识别结果，
     * 原来在 for 循环里逐条 {@code insert} → 10 万张图就是 20 万条 INSERT。
     *
     * 为什么不用 MP 的 {@code saveBatch} / {@code Db.saveBatch}：
     * 那两个走的是 JDBC 批量（{@code ExecutorType.BATCH}），只有在连接串带
     * {@code rewriteBatchedStatements=true} 时才会被驱动合并成一条多行 INSERT。
     * 本项目的 JDBC URL 没有该参数（见 application.yml），
     * 所以走 JDBC 批量在 MySQL 侧仍然是 N 条语句，达不到"真批量"的效果。
     * 这里注入的是 MP 官方的 {@link InsertBatchSomeColumn}，直接在 SQL 层拼多行 VALUES，
     * 不依赖任何连接参数。
     *
     * 注意：{@code getMethodList} 在 3.5.7 是**三参**签名，且父类返回的列表可能不可变，
     * 因此用 {@code new ArrayList<>(...)} 包一层再追加，避免 UnsupportedOperationException。
     * （第一个参数是 MyBatis 的 Configuration，与类上的 Spring {@code @Configuration} 同名，
     * 故此处用全限定名书写，避免 import 冲突。）
     */
    @Bean
    public ISqlInjector sqlInjector() {
        return new DefaultSqlInjector() {
            @Override
            public List<AbstractMethod> getMethodList(
                    org.apache.ibatis.session.Configuration configuration,
                    Class<?> mapperClass,
                    TableInfo tableInfo) {
                List<AbstractMethod> methods =
                        new ArrayList<>(super.getMethodList(configuration, mapperClass, tableInfo));
                methods.add(new InsertBatchSomeColumn());
                return methods;
            }
        };
    }
}
