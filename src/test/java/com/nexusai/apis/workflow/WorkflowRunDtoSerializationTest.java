package com.nexusai.apis.workflow;

import com.nexusai.application.agent.workflow.progress.RunProgress;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowRunDto Jackson 序列化意图测试（前端联调前置）。
 *
 * <p><b>WHY（规则九）</b>：前端联调发现 TypeNotPresentException
 * {@code WorkflowRunDto$PhaseDto not present}——需钉死嵌套 record（PhaseDto/AgentDto）
 * 在 Jackson 序列化下可用（record 隐式 static，泛型 List<PhaseDto> 签名可解析）。
 * 若嵌套类非 static 或编译产物缺失，序列化即抛 TypeNotPresent。
 */
class WorkflowRunDtoSerializationTest {

    @Test
    void serializesNestedRecords() throws Exception {
        RunProgress run = RunProgress.builder()
            .runId("r-1").workflowName("spec")
            .status(RunProgress.Status.COMPLETED)
            .phases(List.of(new RunProgress.Phase("Doc", RunProgress.PhaseState.DONE)))
            .declaredPhases(List.of("Doc")).currentPhase(null)
            .agents(List.of()).agentCount(0)
            .startedAt(1000L).updatedAt(2000L).build();
        WorkflowRunDto dto = WorkflowRunDto.from(run);

        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(dto);

        assertThat(json).contains("\"runId\":\"r-1\"");
        assertThat(json).contains("\"phases\"");
        assertThat(json).contains("\"title\":\"Doc\"");
        assertThat(json).contains("\"status\":\"done\"");
    }
}
