package com.hhx.agi.application.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

/**
 * Agent 流式事件 —— 通过 SSE 推送给前端。
 *
 * 主链路是 Agentic Loop，当前采用 ReAct 风格：
 *   thinking → tool_call → observation → ... → ask_user/final_answer
 *
 * plan/replan 仅保留给旧 Plan-and-Execute 执行器兼容使用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentStreamEvent(
        Type type,
        String message,
        Integer step,
        Integer totalSteps,
        Status status,
        List<String> steps,
        String content
) {
    public enum Type {
        TASK_START("task_start"),
        THINKING("thinking"),
        PLAN("plan"),
        TOOL_CALL("tool_call"),
        OBSERVATION("observation"),
        REPLAN("replan"),
        ASK_USER("ask_user"),
        FINAL_ANSWER("final_answer"),
        ERROR("error"),
        DONE("done");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        @JsonValue
        public String wireName() {
            return wireName;
        }
    }

    public enum Status {
        RUNNING("running"),
        DONE("done");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        @JsonValue
        public String wireName() {
            return wireName;
        }
    }

    /** 多意图：开始处理某个子任务 */
    public static AgentStreamEvent taskStart(int current, int total, String skillName, String subTask) {
        String msg = String.format("📋 任务 %d/%d [%s]: %s", current, total, skillName, subTask);
        return new AgentStreamEvent(Type.TASK_START, msg, current, total, null, null, null);
    }

    /** Agent 正在思考下一步 */
    public static AgentStreamEvent thinking(String message) {
        return new AgentStreamEvent(Type.THINKING, message, null, null, null, null, null);
    }

    /** 旧 Plan-and-Execute：生成执行计划 */
    public static AgentStreamEvent plan(List<String> steps) {
        return new AgentStreamEvent(Type.PLAN, null, null, steps.size(), null, steps, null);
    }

    /** 工具调用开始 */
    public static AgentStreamEvent toolCallStart(int step, int total, String toolName) {
        return new AgentStreamEvent(Type.TOOL_CALL, toolName, step, total, Status.RUNNING, null, null);
    }

    /** 工具调用完成 */
    public static AgentStreamEvent toolCallDone(int step, int total, String toolName, String result) {
        return new AgentStreamEvent(Type.TOOL_CALL, toolName, step, total, Status.DONE, null, result);
    }

    /** 观察工具调用结果并决定下一步 */
    public static AgentStreamEvent observation(int step, String observation) {
        return new AgentStreamEvent(Type.OBSERVATION, observation, step, null, null, null, null);
    }

    /** 旧 Plan-and-Execute：重新规划（附带新步骤列表） */
    public static AgentStreamEvent replan(String reason, List<String> newSteps) {
        return new AgentStreamEvent(Type.REPLAN, reason, null, newSteps.size(), null, newSteps, null);
    }

    /** 需要用户补充信息（结构化追问信号，供调用方可靠性检测而非推断） */
    public static AgentStreamEvent askUser(String question) {
        return new AgentStreamEvent(Type.ASK_USER, question, null, null, null, null, null);
    }

    /** 最终回复 */
    public static AgentStreamEvent finalAnswer(String content) {
        return new AgentStreamEvent(Type.FINAL_ANSWER, null, null, null, null, null, content);
    }

    /** 执行出错 */
    public static AgentStreamEvent error(String message) {
        return new AgentStreamEvent(Type.ERROR, message, null, null, null, null, null);
    }

    /** 全部完成 */
    public static AgentStreamEvent done() {
        return new AgentStreamEvent(Type.DONE, null, null, null, null, null, null);
    }
}
