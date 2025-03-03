package io.kestra.plugin.core.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.Label;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.tasks.ExecutionUpdatableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.kestra.core.models.Label.SYSTEM_PREFIX;
import static io.kestra.core.utils.Rethrow.throwBiConsumer;
import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Allow to add or overwrite labels for the current execution at runtime.",
    description = "Trying to pass a system label (a label starting with `system_`) will fail the task."
)
@Plugin(
    examples = {
        @Example(
            title = "Add labels based on a webhook payload",
            full = true,
            code = {
                "id: webhook_based_labels",
                "namespace: company.team",
                "tasks:",
                "  - id: update_labels_with_map",
                "    type: io.kestra.plugin.core.execution.Labels",
                "    labels:",
                "      customerId: \"{{ trigger.body.customerId }}\"",
                "  - id: by_list",
                "    type: io.kestra.plugin.core.execution.Labels",
                "    labels:",
                "      - key: order_id",
                "        value: \"{{ trigger.body.orderId }}\"",
                "      - key: order_type",
                "        value: \"{{ trigger.body.orderType }}\"",
                "triggers:",
                "  - id: webhook",
                "    key: order_webhook",
                "    type: io.kestra.plugin.core.trigger.Webhook",
                "    conditions:",
                "      - type: io.kestra.plugin.core.condition.ExpressionCondition",
                "        expression: \"{{ trigger.body.customerId is defined and trigger.body.orderId is defined and trigger.body.orderType is defined }}\""
            }
        )
    },
    aliases = "io.kestra.core.tasks.executions.Labels"
)
public class Labels extends Task implements ExecutionUpdatableTask {
    @Schema(
        title = "Labels to add to the current execution.",
        description = "The value should result in a list of labels or a labelKey:labelValue map",
        oneOf = {
            String.class,
            Label[].class,
            Map.class
        }
    )
    @PluginProperty(dynamic = true, additionalProperties = String.class)
    @NotNull
    private Object labels;

    @Override
    public Execution update(Execution execution, RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        Object labelsObject = labels;
        if (labels instanceof String labelStr) {
            try {
                labelsObject = JacksonMapper.toObject(runContext.render(labelStr));
            } catch (JsonProcessingException e) {
                throw new IllegalVariableEvaluationException(e);
            }
        }

        if (labelsObject instanceof List<?> labelsList) {
            labelsObject = labelsList.stream()
                .map(throwFunction(label -> {
                        if (label instanceof Map<?, ?> labelMap) {
                            return Map.entry(
                                (String) labelMap.get("key"),
                                (String) labelMap.get("value")
                            );
                        } else {
                            throw new IllegalVariableEvaluationException("Unknown value type: " + label.getClass());
                        }
                    })
                ).collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue
                ));
        }

        if (labelsObject instanceof Map<?, ?> labelsMap) {
            Map<String, String> newLabels = Optional.ofNullable(execution.getLabels()).orElse(Collections.emptyList()).stream()
                .collect(Collectors.toMap(
                    Label::key,
                    Label::value,
                    (labelValue1, labelValue2) -> labelValue2
                ));
            labelsMap.forEach(throwBiConsumer((key, value) -> {
                String renderedKey = runContext.render((String) key);
                String renderedValue = runContext.render((String) value);
                logger.info("Adding label {} with value {}", renderedKey, renderedValue);
                newLabels.put(
                    renderedKey,
                    renderedValue
                );
            }));

            // check for system labels: none can be passed at runtime
            Optional<Map.Entry<String, String>> first = newLabels.entrySet().stream().filter(entry -> entry.getKey().startsWith(SYSTEM_PREFIX)).findFirst();
            if (first.isPresent()) {
                throw new IllegalArgumentException("System labels can only be set by Kestra itself, offending label: " + first.get().getKey() + "=" + first.get().getValue());
            }

            return execution.withLabels(newLabels.entrySet().stream()
                    .map(entry -> new Label(
                        entry.getKey(),
                        entry.getValue()
                    ))
                    .toList());
        } else {
            throw new IllegalVariableEvaluationException("Unknown value type: " + labels.getClass());
        }
    }
}
