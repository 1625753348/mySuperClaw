package com.example.enterpriseagent.runtime.planner;

import com.example.enterpriseagent.runtime.RunModel.PlannerContext;

import java.util.List;

/** 模型只选择本次分析维度，节点操作、输入绑定和权限仍由运行时决定。 */
public interface RunPlanner {
    List<String> chooseDimensions(PlannerContext context);
    String version();
}
