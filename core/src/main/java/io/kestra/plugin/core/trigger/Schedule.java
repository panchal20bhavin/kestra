package io.kestra.plugin.core.trigger;

import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.google.common.collect.ImmutableMap;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.exceptions.InternalException;
import io.kestra.core.models.Label;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.Condition;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.conditions.ScheduleCondition;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.services.ConditionService;
import io.kestra.core.services.LabelService;
import io.kestra.core.utils.ListUtils;
import io.kestra.core.validations.ScheduleValidation;
import io.kestra.core.validations.TimezoneId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Schedule a flow based on a CRON expression.",
    description = "You can add multiple schedule(s) to a flow.\n" +
        "The scheduler keeps track of the last scheduled date, allowing you to easily backfill missed executions.\n" +
        "Keep in mind that if you change the trigger ID, the scheduler will consider this as a new schedule, and will start creating new scheduled executions from the current date.\n" +
        "By default, Schedules will use UTC. If you need a different timezone, use the `timezone` property to update it."
)
@Plugin(
    examples = {
        @Example(
            title = "Schedule a flow every 15 minutes.",
            full = true,
            code = """
            id: scheduled_flow
            namespace: company.team

            tasks:
              - id: sleep_randomly
                type: io.kestra.plugin.scripts.shell.Commands
                runner: PROCESS
                commands:
                  - echo "{{ execution.startDate ?? trigger.date }}"
                  - sleep $((RANDOM % 60 + 1))

            triggers:
              - id: every_15_minutes
                type: io.kestra.plugin.core.trigger.Schedule
                cron: '*/15 * * * *'
            """
        ),
        @Example(
            title = "Schedule a flow every hour using the cron nickname `@hourly`.",
            code = """
                id: scheduled_flow
                namespace: company.team

                tasks:
                  - id: log_hello_world
                    type: io.kestra.plugin.core.log.Log
                    message: Hello World! 🚀

                triggers:
                  - id: hourly
                    type: io.kestra.plugin.core.trigger.Schedule
                    cron: "@hourly"
                """,
            full = true
        ),
        @Example(
            title = "Schedule a flow on the first Monday of the month at 11 AM.",
            code = """
                id: scheduled_flow
                namespace: company.team

                tasks:
                  - id: log_hello_world
                    type: io.kestra.plugin.core.log.Log
                    message: Hello World! 🚀

                triggers:
                  - id: schedule
                    cron: "0 11 * * 1"
                    conditions:
                      - type: io.kestra.plugin.core.condition.DayWeekInMonthCondition
                        date: "{{ trigger.date }}"
                        dayOfWeek: "MONDAY"
                        dayInMonth: "FIRST"
                """,
            full = true
        ),
        @Example(
            title = "Schedule a flow every day at 9:00 AM and pause a schedule trigger after a failed execution using the `stopAfter` property.",
            full = true,
            code = """
            id: business_critical_flow
            namespace: company.team

            tasks:
              - id: important_task
                type: io.kestra.plugin.core.log.Log
                message: "if this run fails, disable the schedule until the issue is fixed"

            triggers:
              - id: stop_after_failed
                type: io.kestra.plugin.core.trigger.Schedule
                cron: "0 9 * * *"
                stopAfter:
                  - FAILED"""
        ),
    },
    aliases = "io.kestra.core.models.triggers.types.Schedule"
)
@ScheduleValidation
public class Schedule extends AbstractTrigger implements Schedulable, TriggerOutput<Schedule.Output> {
    private static final CronDefinitionBuilder CRON_DEFINITION_BUILDER = CronDefinitionBuilder.defineCron()
        .withMinutes().withValidRange(0, 59).withStrictRange().and()
        .withHours().withValidRange(0, 23).withStrictRange().and()
        .withDayOfMonth().withValidRange(1, 31).withStrictRange().and()
        .withMonth().withValidRange(1, 12).withStrictRange().and()
        .withDayOfWeek().withValidRange(0, 7).withMondayDoWValue(1).withIntMapping(7, 0).withStrictRange().and()
        .withSupportedNicknameYearly()
        .withSupportedNicknameAnnually()
        .withSupportedNicknameMonthly()
        .withSupportedNicknameWeekly()
        .withSupportedNicknameDaily()
        .withSupportedNicknameMidnight()
        .withSupportedNicknameHourly();

    private static final CronParser CRON_PARSER = new CronParser(CRON_DEFINITION_BUILDER.instance());
    private static final CronParser CRON_PARSER_WITH_SECONDS = new CronParser(CRON_DEFINITION_BUILDER.withSeconds().withValidRange(0, 59).withStrictRange().and().instance());

    @NotNull
    @Schema(
        title = "The cron expression.",
        description = "A standard [unix cron expression](https://en.wikipedia.org/wiki/Cron) with 5 fields (minutes precision). Using `withSeconds: true` you can switch to 6 fields and a seconds precision.\n" +
            "Can also be a cron extension / nickname:\n" +
            "* `@yearly`\n" +
            "* `@annually`\n" +
            "* `@monthly`\n" +
            "* `@weekly`\n" +
            "* `@daily`\n" +
            "* `@midnight`\n" +
            "* `@hourly`"
    )
    @PluginProperty
    private String cron;

    @Schema(
        title = "Whether the cron expression has seconds precision",
        description = "By default, the cron expression has 5 fields, setting this property to true will allow a 6th fields for seconds precision."
    )
    @NotNull
    @Builder.Default
    @PluginProperty
    private Boolean withSeconds = false;

    @TimezoneId
    @Schema(
        title = "The [time zone identifier](https://en.wikipedia.org/wiki/List_of_tz_database_time_zones) (i.e. the second column in [the Wikipedia table](https://en.wikipedia.org/wiki/List_of_tz_database_time_zones#List)) to use for evaluating the cron expression. Default value is the server default zone ID."
    )
    @PluginProperty
    @Builder.Default
    private String timezone = ZoneId.systemDefault().toString();

    @Schema(hidden = true)
    @Builder.Default
    @Null
    private final Duration interval = null;

    @Valid
    @Schema(
        title = "(Deprecated) Conditions on date. Use `conditions` instead.",
        description = "List of schedule conditions in order to limit the schedule trigger date."
    )
    @PluginProperty
    @Deprecated
    private List<ScheduleCondition> scheduleConditions;

    @Schema(
        title = "The inputs to pass to the scheduled flow."
    )
    @PluginProperty(dynamic = true)
    private Map<String, Object> inputs;

    @Schema(
        title = "The maximum delay that is accepted.",
        description = "If the scheduled execution didn't start after this delay (e.g. due to infrastructure issues), the execution will be skipped."
    )
    @PluginProperty
    private Duration lateMaximumDelay;

    @Getter(AccessLevel.NONE)
    private transient ExecutionTime executionTime;

    @Schema(
        title = "(Deprecated) Backfill",
        description = "This property is deprecated and will be removed in the future. Instead, you can now go to the Triggers tab and start a highly customizable backfill process directly from the UI. This will allow you to backfill missed scheduled executions by providing a specific date range and custom labels. Read more about it in the [Backfill](https://kestra.io/docs/concepts/backfill) documentation."
    )
    @PluginProperty
    @Deprecated
    private Map<String, Object> backfill;

    @Schema(
        title = "What to do in case of missed schedules",
        description = "`ALL` will recover all missed schedules, `LAST`  will only recovered the last missing one, `NONE` will not recover any missing schedule.\n" +
            "The default is `ALL` unless a different value is configured using the global plugin configuration."
    )
    @PluginProperty
    private RecoverMissedSchedules recoverMissedSchedules;

    @Override
    public List<Condition> getConditions() {
        List<Condition> conditions = Stream.concat(ListUtils.emptyOnNull(this.conditions).stream(),
            ListUtils.emptyOnNull(this.scheduleConditions).stream().map(c -> (Condition) c)).toList();
        return conditions.isEmpty() ? null : conditions;
    }

    @Override
    public ZonedDateTime nextEvaluationDate(ConditionContext conditionContext, Optional<? extends TriggerContext> last) {
        ExecutionTime executionTime = this.executionTime();
        ZonedDateTime nextDate;
        Backfill backfill = null;
        if (last.isPresent() && (last.get().getBackfill() != null || last.get().getDate() != null)) {
            ZonedDateTime lastDate;
            if (last.get().getBackfill() != null) {
                backfill = last.get().getBackfill();
                lastDate = convertDateTime(backfill.getCurrentDate());
            } else {
                lastDate = convertDateTime(last.get().getDate());
            }

            // previous present & conditions
            if (this.getConditions() != null) {
                try {
                    Optional<ZonedDateTime> next = this.truePreviousNextDateWithCondition(
                        executionTime,
                        conditionContext,
                        lastDate,
                        true
                    );

                    if (next.isPresent()) {
                        return next.get().truncatedTo(ChronoUnit.SECONDS);
                    }
                } catch (InternalException e) {
                    conditionContext.getRunContext().logger().warn("Unable to evaluate the conditions for the next evaluation date for trigger '{}', conditions will not be evaluated", this.getId());
                }
            }

            // previous present but no conditions
            nextDate = computeNextEvaluationDate(executionTime, lastDate).orElse(null);

            // if we have a current backfill but the nextDate
            // is after the end, then we calculate again the nextDate
            // based on now()
            if (backfill != null && nextDate != null && nextDate.isAfter(backfill.getEnd())) {
                nextDate = computeNextEvaluationDate(executionTime, convertDateTime(ZonedDateTime.now())).orElse(null);
            }
        }
        // no previous present & no backfill or recover missed schedules, just provide now
        else {
            nextDate = computeNextEvaluationDate(executionTime, convertDateTime(ZonedDateTime.now())).orElse(null);
        }

        // if max delay reached, we calculate a new date except if we are doing a backfill
        if (this.lateMaximumDelay != null && nextDate != null && backfill == null) {
            Output scheduleDates = this.scheduleDates(executionTime, nextDate).orElse(null);
            scheduleDates = this.handleMaxDelay(scheduleDates);
            if (scheduleDates != null) {
                nextDate = scheduleDates.getDate();
            } else {
                return null;
            }
        }

        return nextDate;
    }

    @Override
    public ZonedDateTime nextEvaluationDate() {
        // it didn't take into account the schedule condition, but as they are taken into account inside eval() it's OK.
        ExecutionTime executionTime = this.executionTime();
        return computeNextEvaluationDate(executionTime, convertDateTime(ZonedDateTime.now())).orElse(convertDateTime(ZonedDateTime.now()));
    }

    @Override
    public ZonedDateTime previousEvaluationDate(ConditionContext conditionContext) {
        ExecutionTime executionTime = this.executionTime();
        if (this.getConditions() != null) {
            try {
                Optional<ZonedDateTime> previous = this.truePreviousNextDateWithCondition(
                    executionTime,
                    conditionContext,
                    ZonedDateTime.now(),
                    false
                );

                if (previous.isPresent()) {
                    return previous.get().truncatedTo(ChronoUnit.SECONDS);
                }
            } catch (InternalException e) {
                conditionContext.getRunContext().logger().warn("Unable to evaluate the conditions for the next evaluation date for trigger '{}', conditions will not be evaluated", this.getId());
            }
        }
        return computePreviousEvaluationDate(executionTime, convertDateTime(ZonedDateTime.now())).orElse(convertDateTime(ZonedDateTime.now()));
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext triggerContext) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        ExecutionTime executionTime = this.executionTime();
        ZonedDateTime currentDateTimeExecution = convertDateTime(triggerContext.getDate());
        Backfill backfill = triggerContext.getBackfill();

        if (backfill != null) {
            if (backfill.getPaused()) {
                return Optional.empty();
            }
            currentDateTimeExecution = backfill.getCurrentDate();
        }

        Output scheduleDates = this.scheduleDates(executionTime, currentDateTimeExecution).orElse(null);

        if (scheduleDates == null || scheduleDates.getDate() == null) {
            return Optional.empty();
        }

        ZonedDateTime next = scheduleDates.getDate();

        // we are in the future don't allow
        // No use case, just here for prevention but it should never happen
        if (next.compareTo(ZonedDateTime.now().plus(Duration.ofSeconds(1))) > 0) {
            if (log.isTraceEnabled()) {
                log.trace("Schedule is in the future, execution skipped, this behavior should never happen.");
            }
            return Optional.empty();
        }

        // inject outputs variables for scheduleCondition
        conditionContext = conditionContext(conditionContext, scheduleDates);

        // control conditions
        if (this.getConditions() != null) {
            try {
                boolean conditionResults = this.validateScheduleCondition(conditionContext);
                if (!conditionResults) {
                    return Optional.empty();
                }
            } catch(InternalException ie) {
                // validate schedule condition can fail to render variables
                // in this case, we return a failed execution so the trigger is not evaluated each second
                runContext.logger().error("Unable to evaluate the Schedule trigger '{}'", this.getId(), ie);
                Execution execution = Execution.builder()
                    .id(runContext.getTriggerExecutionId())
                    .tenantId(triggerContext.getTenantId())
                    .namespace(triggerContext.getNamespace())
                    .flowId(triggerContext.getFlowId())
                    .flowRevision(conditionContext.getFlow().getRevision())
                    .labels(generateLabels(runContext, conditionContext, backfill))
                    .state(new State().withState(State.Type.FAILED))
                    .build();
                return Optional.of(execution);
            }

            // recalculate true output for previous and next based on conditions
            scheduleDates = this.trueOutputWithCondition(executionTime, conditionContext, scheduleDates);
        }

        Map<String, Object> variables;
        if (this.timezone != null) {
            variables = scheduleDates.toMap(ZoneId.of(this.timezone));
        } else {
            variables = scheduleDates.toMap();
        }

        Execution execution = TriggerService.generateScheduledExecution(
            this,
            conditionContext,
            triggerContext,
            generateLabels(runContext, conditionContext, backfill),
            generateInputs(runContext, backfill),
            variables,
            Optional.empty()
        );

       execution = execution.toBuilder()
            // keep to avoid breaking compatibility
            .variables(ImmutableMap.of(
                "schedule", execution.getTrigger().getVariables()
            ))
            .build();

       return Optional.of(execution);
    }

    public Cron parseCron() {
        CronParser parser = Boolean.TRUE.equals(withSeconds) ? CRON_PARSER_WITH_SECONDS : CRON_PARSER;
        return parser.parse(this.cron);
    }

    private List<Label> generateLabels(RunContext runContext, ConditionContext conditionContext, Backfill backfill) throws IllegalVariableEvaluationException {
        List<Label> labels = LabelService.fromTrigger(runContext, conditionContext.getFlow(), this);

        if (backfill != null && backfill.getLabels() != null) {
            for (Label label : backfill.getLabels()) {
                final var value = runContext.render(label.value());
                if (value != null) {
                    labels.add(new Label(label.key(), value));
                }
            }
        }

        return labels;
    }

    private Map<String, Object> generateInputs(RunContext runContext, Backfill backfill) throws IllegalVariableEvaluationException {
        Map<String, Object> inputs = new HashMap<>();

        if (this.inputs != null) {
            inputs.putAll(runContext.render(this.inputs));
        }

        if (backfill != null && backfill.getInputs() != null) {
            inputs.putAll(runContext.render(backfill.getInputs()));
        }

        return inputs;
    }
    private Optional<Output> scheduleDates(ExecutionTime executionTime, ZonedDateTime date) {
        Optional<ZonedDateTime> next = executionTime.nextExecution(date.minus(Duration.ofSeconds(1)));

        if (next.isEmpty()) {
            return Optional.empty();
        }

        Output.OutputBuilder<?, ?> outputDatesBuilder = Output.builder()
            .date(convertDateTime(next.get()));

        computeNextEvaluationDate(executionTime, next.get())
            .map(this::convertDateTime)
            .ifPresent(outputDatesBuilder::next);

        executionTime.lastExecution(date)
            .map(this::convertDateTime)
            .ifPresent(outputDatesBuilder::previous);

        Output scheduleDates = outputDatesBuilder.build();

        return Optional.of(scheduleDates);
    }

    private ConditionContext conditionContext(ConditionContext conditionContext, Output output) {
        return conditionContext.withVariables(ImmutableMap.of(
            "schedule", output.toMap(),
            "trigger", output.toMap()
        ));
    }

    private synchronized ExecutionTime executionTime() {
        if (this.executionTime == null) {
            Cron parsed = parseCron();
            this.executionTime = ExecutionTime.forCron(parsed);
        }

        return this.executionTime;
    }

    private ZonedDateTime convertDateTime(ZonedDateTime date) {
        if (this.timezone == null) {
            return date;
        }

        return date.withZoneSameInstant(ZoneId.of(this.timezone));
    }

    private Optional<ZonedDateTime> computeNextEvaluationDate(ExecutionTime executionTime, ZonedDateTime date) {
        return executionTime.nextExecution(date).map(zonedDateTime -> zonedDateTime.truncatedTo(ChronoUnit.SECONDS));
    }

    private Optional<ZonedDateTime> computePreviousEvaluationDate(ExecutionTime executionTime, ZonedDateTime date) {
        return executionTime.lastExecution(date).map(zonedDateTime -> zonedDateTime.truncatedTo(ChronoUnit.SECONDS));
    }

    private Output trueOutputWithCondition(ExecutionTime executionTime, ConditionContext conditionContext, Output output) throws InternalException {
        Output.OutputBuilder<?, ?> outputBuilder = Output.builder()
            .date(output.getDate());

        this.truePreviousNextDateWithCondition(executionTime, conditionContext, output.getDate(), true)
            .ifPresent(outputBuilder::next);

        this.truePreviousNextDateWithCondition(executionTime, conditionContext, output.getDate(), false)
            .ifPresent(outputBuilder::previous);

        return outputBuilder.build();
    }

    private Optional<ZonedDateTime> truePreviousNextDateWithCondition(ExecutionTime executionTime, ConditionContext conditionContext, ZonedDateTime toTestDate, boolean next) throws InternalException {
        while (
            (next && toTestDate.getYear() < ZonedDateTime.now().getYear() + 10) ||
                (!next && toTestDate.getYear() > ZonedDateTime.now().getYear() - 10)
        ) {
            Optional<ZonedDateTime> currentDate = next ?
                executionTime.nextExecution(toTestDate) :
                executionTime.lastExecution(toTestDate);

            if (currentDate.isEmpty()) {
                return currentDate;
            }

            Optional<Output> currentOutput = this.scheduleDates(executionTime, currentDate.get());

            if (currentOutput.isEmpty()) {
                return Optional.empty();
            }

            ConditionContext currentConditionContext = this.conditionContext(conditionContext, currentOutput.get());

            boolean conditionResults = this.validateScheduleCondition(currentConditionContext);
            if (conditionResults) {
                return currentDate;
            }

            toTestDate = currentDate.get();
        }

        return Optional.empty();
    }

    private Output handleMaxDelay(Output output) {
        if (output == null) {
            return null;
        }

        if (this.lateMaximumDelay == null) {
            return output;
        }

        while (
            (output.getDate().getYear() < ZonedDateTime.now().getYear() + 10) ||
                (output.getDate().getYear() > ZonedDateTime.now().getYear() - 10)
        ) {
            if (output.getDate().plus(this.lateMaximumDelay).compareTo(ZonedDateTime.now()) < 0) {
                output = this.scheduleDates(executionTime, output.getNext()).orElse(null);
                if (output == null) {
                    return null;
                }
            } else {
                return output;
            }
        }

        return output;
    }

    private boolean validateScheduleCondition(ConditionContext conditionContext) throws InternalException {
        if (conditions != null) {
            ConditionService conditionService = ((DefaultRunContext)conditionContext.getRunContext()).getApplicationContext().getBean(ConditionService.class);
            return conditionService.isValid(
                conditions.stream().filter(c -> c instanceof ScheduleCondition).map(c -> (ScheduleCondition) c).toList(),
                conditionContext
            );
        }

        return true;
    }

    @SuperBuilder
    @ToString
    @EqualsAndHashCode
    @Getter
    @NoArgsConstructor
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The date of the current schedule.")
        @NotNull
        private ZonedDateTime date;

        @Schema(title = "The date of the next schedule.")
        @NotNull
        private ZonedDateTime next;

        @Schema(title = "The date of the previous schedule.")
        @NotNull
        private ZonedDateTime previous;
    }
}
