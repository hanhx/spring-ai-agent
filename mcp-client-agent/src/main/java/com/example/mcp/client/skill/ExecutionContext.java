package com.example.mcp.client.skill;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan-and-Execute 执行上下文 —— 封装一次 Skill 执行过程中的所有可变状态
 *
 * 替代原来 SkillExecutor 中散落的 int[]、boolean[]、List[] 等 hack，
 * 提供类型安全的状态管理和便捷的操作方法。
 */
public class ExecutionContext {

    /** 步骤执行结果 */
    public record StepResult(String step, String result) {}

    private final SkillDefinition skill;
    private final String conversationId;
    private final String userMessage;
    private final String enrichedMessage;

    /** 缓存的 Skill prompt 和工具（预加载一次，避免重复 IO） */
    private String cachedPrompt;
    private ToolCallback[] cachedTools;

    /** 执行状态 */
    private final List<StepResult> completedSteps = new ArrayList<>();
    private final List<List<String>> planHistory = new ArrayList<>();
    private int stepIndex = 0;
    private int replanCount = 0;
    private int askUserCount = 0;
    private boolean askUserTerminated = false;

    public ExecutionContext(SkillDefinition skill, String conversationId,
                           String userMessage, String enrichedMessage) {
        this.skill = skill;
        this.conversationId = conversationId;
        this.userMessage = userMessage;
        this.enrichedMessage = enrichedMessage;
    }

    // ===== Getters =====

    public SkillDefinition skill() { return skill; }
    public String conversationId() { return conversationId; }
    public String userMessage() { return userMessage; }
    public String enrichedMessage() { return enrichedMessage; }
    public String cachedPrompt() { return cachedPrompt; }
    public ToolCallback[] cachedTools() { return cachedTools; }
    public List<StepResult> completedSteps() { return completedSteps; }
    public int stepIndex() { return stepIndex; }
    public int replanCount() { return replanCount; }
    public int askUserCount() { return askUserCount; }
    public boolean isAskUserTerminated() { return askUserTerminated; }

    // ===== 状态操作 =====

    public void setCachedPrompt(String prompt) { this.cachedPrompt = prompt; }
    public void setCachedTools(ToolCallback[] tools) { this.cachedTools = tools; }

    public void addCompletedStep(String step, String result) {
        completedSteps.add(new StepResult(step, result));
    }

    public void addPlan(List<String> steps) {
        planHistory.add(steps);
    }

    public List<String> currentPlan() {
        return planHistory.get(planHistory.size() - 1);
    }

    public String currentStep() {
        List<String> plan = currentPlan();
        return stepIndex < plan.size() ? plan.get(stepIndex) : null;
    }

    public int currentStepDisplay() { return stepIndex + 1; }
    public int totalSteps() { return currentPlan().size(); }
    public boolean hasMoreSteps() { return stepIndex < currentPlan().size(); }

    public void advanceStep() { stepIndex++; }

    public void incrementReplan() { replanCount++; }

    public void resetForReplan(List<String> newSteps) {
        addPlan(newSteps);
        stepIndex = 0;
        completedSteps.clear();
    }

    public int incrementAskUser() { return ++askUserCount; }

    public void terminateWithAskUser() { askUserTerminated = true; }
}
