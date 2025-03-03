<template>
    <div class="p-4">
        <div class="d-flex justify-content-between pb-4">
            <div>
                <p class="m-0 fs-6">
                    <span class="fw-bold">{{ t("logs") }}</span>
                </p>
            </div>

            <div>
                <div id="logs" />
            </div>
        </div>

        <Bar
            v-if="props.data.length > 0"
            :data="parsedData"
            :options="options"
            :plugins="[barLegend]"
            class="tall"
        />
        <el-empty v-else :description="$t('no_data')" />
    </div>
</template>

<script setup>
    import {computed} from "vue";
    import {useI18n} from "vue-i18n";

    import moment from "moment";
    import {Bar} from "vue-chartjs";

    import {barLegend} from "../legend.js";

    import {defaultConfig, getFormat} from "../../../../../utils/charts.js";
    import {getScheme} from "../../../../../utils/scheme.js";
    import Logs from "../../../../../utils/logs.js";

    const {t} = useI18n({useScope: "global"});

    const props = defineProps({
        data: {
            type: Array,
            required: true,
        },
    });

    const parsedData = computed(() => {
        let datasets = props.data.reduce(function (accumulator, value) {
            Object.keys(value.counts).forEach(function (state) {
                if (accumulator[state] === undefined) {
                    accumulator[state] = {
                        label: state,
                        backgroundColor: getScheme(state, "logs"),
                        yAxisID: "y",
                        data: [],
                    };
                }

                accumulator[state].data.push(value.counts[state]);
            });

            return accumulator;
        }, Object.create(null));

        datasets = Logs.sort(datasets);

        return {
            labels: props.data.map((r) =>
                moment(r.timestamp).format(getFormat(r.groupBy)),
            ),
            datasets: Object.values(datasets),
        };
    });

    const options = computed(() =>
        defaultConfig({
            barThickness: 12,
            skipNull: true,
            borderSkipped: false,
            borderColor: "transparent",
            borderWidth: 2,
            plugins: {
                barLegend: {
                    containerID: "logs",
                },
                tooltip: {
                    enabled: true,
                    filter: (value) => value.raw,
                    callbacks: {
                        label: (value) => {
                            const {label} = value.dataset;
                            return `${label.toLowerCase().capitalize()}: ${value.raw}`;
                        },
                        footer: (value) => {
                            const total = value.reduce((a, c) => a + c.raw, 0);
                            return `${t("Total")}: ${total}`;
                        },
                    },
                },
            },
            scales: {
                x: {
                    title: {
                        display: true,
                        text: t("date"),
                    },
                    grid: {
                        display: false,
                    },
                    position: "bottom",
                    display: true,
                    stacked: true,
                    ticks: {
                        maxTicksLimit: 8,
                        callback: function (value) {
                            return moment(
                                new Date(this.getLabelForValue(value)),
                            ).format("MM/DD");
                        },
                    },
                },
                y: {
                    title: {
                        display: true,
                        text: t("logs"),
                    },
                    grid: {
                        display: false,
                    },
                    display: true,
                    position: "left",
                    stacked: true,
                    ticks: {
                        maxTicksLimit: 8,
                    },
                },
            },
        }),
    );
</script>

<style lang="scss" scoped>
@import "@kestra-io/ui-libs/src/scss/variables";

$height: 200px;

.tall {
    height: $height;
    max-height: $height;
}

.small {
    font-size: $font-size-xs;
    color: $gray-700;

    html.dark & {
        color: $gray-300;
    }
}
</style>
