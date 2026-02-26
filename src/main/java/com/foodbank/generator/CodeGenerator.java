package com.foodbank.generator;

import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.rules.DbColumnType;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.sql.Types;
import java.util.Collections;

public class CodeGenerator {

    public static void main(String[] args) {

        // 1. 数据库连接配置 (使用你 application.yml 中的参数)
        String url = "jdbc:mysql://localhost:3306/food_bank?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        String username = "root";
        String password = "root";

        // 2. 获取当前项目的根目录路径 (用于存放生成的代码)
        String projectPath = System.getProperty("user.dir");

        FastAutoGenerator.create(url, username, password)
                // 全局配置
                .globalConfig(builder -> {
                    builder.author("Entiesty") // 作者名称 (对应你 OpenAPI 里的名字)
                            .enableSpringdoc() // 开启 springdoc (OpenAPI 3) 模式，自动生成 @Schema 等注解
                            .outputDir(projectPath + "/src/main/java"); // 指定输出目录
                })
                // 包配置
                .packageConfig(builder -> {
                    builder.parent("com.foodbank.module") // 父包名，对应我们的模块化改造
                            .moduleName("dispatch") // ⚠️注意：每次生成不同模块时，修改这里！例如 goods, dispatch, system
                            .pathInfo(Collections.singletonMap(OutputFile.xml, projectPath + "/src/main/resources/mapper")); // XML 映射文件放到 resources 下
                })
                // 策略配置
                .strategyConfig(builder -> {
                    builder
                            // ⚠️注意：每次生成时，把这里改成你要生成的表名
                            .addInclude("fb_order", "fb_task", "fb_credit_log")

                            // 过滤掉表前缀，例如 fb_goods 生成实体类是 Goods 而不是 FbGoods
                            .addTablePrefix("fb_", "sys_")

                            // 实体类策略配置
                            .entityBuilder()
                            .enableLombok() // 开启 Lombok
                            .enableTableFieldAnnotation() // 开启字段注解

                            // 控制层策略配置
                            .controllerBuilder()
                            .enableRestStyle() // 开启 @RestController

                            // Mapper 策略配置
                            .mapperBuilder()
                            .enableMapperAnnotation(); // 开启 @Mapper 注解
                })
                // 使用 Freemarker 模板引擎
                .templateEngine(new FreemarkerTemplateEngine())
                .execute(); // 执行生成
    }
}