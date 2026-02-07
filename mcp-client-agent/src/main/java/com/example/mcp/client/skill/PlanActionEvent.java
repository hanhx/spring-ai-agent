package com.example.mcp.client.skill;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Plan-and-Execute 事件 —— 通过 SSE 推送给前端
 *
 * 参考 LangGraph Plan-and-Execute 模式：
 *   Plan → Execute Step → Observe → (RePlan?) → Execute Next → ... → Final Answer
 *
 * 事件类型：
 * - planning:  正在生成/更新执行计划
 * - plan:      执行计划（包含步骤列表）
 * - action:    正在执行某个步骤（真正调用工具）
 * - observe:   观察步骤执行结果
 * - replan:    根据观察结果重新规划
 * - result:    最终回复
 * - error:     执行出错
 * - done:      全部完成
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanActionEvent(
        String type,
        String message,
        Integer step,
        Integer totalSteps,
        String status,
        List<String> steps,
        String content
) {
    /** 正在规划 */
    public static PlanActionEvent planning(String message) {
        return new PlanActionEvent("planning", message, null, null, null, null, null);
    }

    /** 生成执行计划 */
    public static PlanActionEvent plan(List<String> steps) {
        return new PlanActionEvent("plan", null, null, steps.size(), null, steps, null);
    }

    /** 步骤开始执行 */
    public static PlanActionEvent actionStart(int step, int total, String message) {
        return new PlanActionEvent("action", message, step, total, "running", null, null);
    }

    /** 步骤执行完成 */
    public static PlanActionEvent actionDone(int step, int total, String message, String result) {
        return new PlanActionEvent("action", message, step, total, "done", null, result);
    }

    /** 观察步骤结果 */
    public static PlanActionEvent observe(int step, String observation) {
        return new PlanActionEvent("observe", observation, step, null, null, null, null);
    }

    /** 重新规划（附带新步骤列表） */
    public static PlanActionEvent replan(String reason, List<String> newSteps) {
        return new PlanActionEvent("replan", reason, null, newSteps.size(), null, newSteps, null);
    }

    /** 最终回复 */
    public static PlanActionEvent result(String content) {
        return new PlanActionEvent("result", null, null, null, null, null, content);
    }

    /** 执行出错 */
    public static PlanActionEvent error(String message) {
        return new PlanActionEvent("error", message, null, null, null, null, null);
    }

    /** 全部完成 */
    public static PlanActionEvent done() {
        return new PlanActionEvent("done", null, null, null, null, null, null);
    }
}
