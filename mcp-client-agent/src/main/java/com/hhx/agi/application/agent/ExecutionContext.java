package com.hhx.agi.application.agent;

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
    private final String model;
    private final String userId;

    /** 缓存的 Skill prompt 和工具（预加载一次，避免重复 IO） */
    private String cachedPrompt;
    private ToolCallback[] cachedTools;

    /** 信息预取阶段收集的上下文（规划前只读工具调用结果） */
    private String explorationContext;

    /** 执行状态 */
    private final List<StepResult> completedSteps = new ArrayList<>();
    private final List<List<String>> planHistory = new ArrayList<>();
    private int stepIndex = 0;
    private int replanCount = 0;
    private int askUserCount = 0;
    private int totalExecutedSteps = 0;
    private boolean askUserTerminated = false;

    public ExecutionContext(SkillDefinition skill, String conversationId,
                           String userMessage, String enrichedMessage, String model, String userId) {
        this.skill = skill;
        this.conversationId = conversationId;
        this.userMessage = userMessage;
        this.enrichedMessage = enrichedMessage;
        this.model = model;
        this.userId = userId;
    }

    // ===== Getters =====

    public SkillDefinition skill() { return skill; }
    public String conversationId() { return conversationId; }
    public String userMessage() { return userMessage; }
    public String enrichedMessage() { return enrichedMessage; }
    public String model() { return model; }
    public String userId() { return userId; }
    public String cachedPrompt() { return cachedPrompt; }
    public ToolCallback[] cachedTools() { return cachedTools; }
    public String explorationContext() { return explorationContext; }
    public List<StepResult> completedSteps() { return completedSteps; }
    public int stepIndex() { return stepIndex; }
    public int replanCount() { return replanCount; }
    public int askUserCount() { return askUserCount; }
    public int totalExecutedSteps() { return totalExecutedSteps; }
    public boolean isAskUserTerminated() { return askUserTerminated; }

    // ===== 状态操作 =====

    public void setCachedPrompt(String prompt) { this.cachedPrompt = prompt; }
    public void setCachedTools(ToolCallback[] tools) { this.cachedTools = tools; }
    public void setExplorationContext(String explorationContext) { this.explorationContext = explorationContext; }

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

    public void incrementExecutedSteps() { totalExecutedSteps++; }

    public void resetForReplan(List<String> newSteps) {
        addPlan(newSteps);
        stepIndex = 0;
    }

    public int incrementAskUser() { return ++askUserCount; }

    public void terminateWithAskUser() { askUserTerminated = true; }
}
