package com.foodbank.module.resource.goods.model;

import java.util.*;

/**
 * 物资分类层级映射表 — 消除硬编码，遵循开闭原则。
 * 新增子类只需修改此枚举，无需改动业务代码。
 */
public enum CategoryHierarchy {

    食品与饮料("米面粮油", "方便速食", "烘焙糕点", "生鲜果蔬", "冷冻食品", "乳制品", "饮用水", "热食盒饭"),
    医疗健康("常备药品", "外用急救", "医疗器械", "营养补品"),
    生活日用("卫生护理", "防寒保暖", "寝具家纺", "洗漱用品", "纸品耗材"),
    应急物资("应急食品", "应急照明", "防护装备", "保暖物资");

    private final List<String> children;

    CategoryHierarchy(String... children) {
        this.children = Collections.unmodifiableList(Arrays.asList(children));
    }

    public List<String> getChildren() {
        return children;
    }

    /**
     * 根据大类名称，展开为包含自身及所有子类的扁平列表。
     * 若大类不在枚举中，返回仅包含自身的单元素列表（预留扩展）。
     */
    public static List<String> expand(String parentCategory) {
        if (parentCategory == null || parentCategory.isBlank()) {
            return Collections.emptyList();
        }
        for (CategoryHierarchy ch : values()) {
            if (ch.name().equals(parentCategory)) {
                List<String> result = new ArrayList<>();
                result.add(parentCategory);
                result.addAll(ch.getChildren());
                return result;
            }
        }
        // 未知分类：保留原值，不丢失数据
        return Collections.singletonList(parentCategory);
    }

    /** 判断该父类是否在已知层级中 */
    public static boolean isKnown(String category) {
        for (CategoryHierarchy ch : values()) {
            if (ch.name().equals(category)) return true;
        }
        return false;
    }
}
