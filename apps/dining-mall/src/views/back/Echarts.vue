<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import request from '../../utils/request'

const goods = ref([])
const pieChartInstance = ref(null)
const pieChartContainer = ref(null)
const barChartInstance = ref(null)
const barChartContainer = ref(null)

const loadGoods = () => {
  request.get('/goods').then(res => {
    goods.value = res.data
  })
}

const loadCategoryData = () => {
  request.get('/echarts/count1').then(res => {
    renderPieChart(res.data)
  })
}

const loadSalesData = () => {
  request.get('/echarts/count2').then(res => {
    renderBarChart(res.data)
  })
}

const renderPieChart = (data) => {
  if (!pieChartContainer.value) return

  if (pieChartInstance.value) {
    pieChartInstance.value.dispose()
  }

  pieChartInstance.value = echarts.init(pieChartContainer.value)

  const option = {
    title: {
      text: '不同分类商品销量分布饼图',
      left: 'center',
      textStyle: {
        fontSize: 16,
        fontWeight: 'bold'
      }
    },
    tooltip: {
      trigger: 'item',
      formatter: '{a} <br/>{b}: {c} ({d}%)'
    },
    legend: {
      orient: 'vertical',
      left: 'left',
      top: '15%'
    },
    series: [
      {
        name: '销量分布',
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['50%', '60%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 10,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: false,
          position: 'center'
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 20,
            fontWeight: 'bold'
          }
        },
        labelLine: {
          show: false
        },
        data: data
      }
    ]
  }

  pieChartInstance.value.setOption(option)
}

const renderBarChart = (data) => {
  if (!barChartContainer.value) return

  if (barChartInstance.value) {
    barChartInstance.value.dispose()
  }

  barChartInstance.value = echarts.init(barChartContainer.value)

  const names = data.map(item => item.name)
  const values = data.map(item => item.value)

  const option = {
    title: {
      text: '最高销售额商品TOP10柱状图',
      left: 'center',
      textStyle: {
        fontSize: 16,
        fontWeight: 'bold'
      }
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow'
      },
      formatter: function (params) {
        const data = params[0]
        return `${data.axisValue}<br/>销售额: ¥${data.value}`
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: '20%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: names,
      axisLabel: {
        rotate: 45,
        interval: 0,
        formatter: function (value) {
          return value.length > 10 ? value.slice(0, 10) + '...' : value;
        }
      },
    },
    yAxis: {
      type: 'value',
      name: '销售额(元)',
      axisLabel: {
        formatter: '¥{value}'
      }
    },
    series: [
      {
        name: '销售额',
        type: 'bar',
        data: values,
        itemStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [{
              offset: 0, color: '#83bff6'
            }, {
              offset: 0.5, color: '#188df0'
            }, {
              offset: 1, color: '#188df0'
            }]
          }
        },
        emphasis: {
          itemStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [{
                offset: 0, color: '#2378f7'
              }, {
                offset: 0.7, color: '#2378f7'
              }, {
                offset: 1, color: '#83bff6'
              }]
            }
          }
        },
        barWidth: '60%'
      }
    ]
  }

  barChartInstance.value.setOption(option)
}

const handleResize = () => {
  if (pieChartInstance.value) {
    pieChartInstance.value.resize()
  }
  if (barChartInstance.value) {
    barChartInstance.value.resize()
  }
}

onMounted(() => {
  loadGoods()
  loadCategoryData()
  loadSalesData()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  if (pieChartInstance.value) {
    pieChartInstance.value.dispose()
  }
  if (barChartInstance.value) {
    barChartInstance.value.dispose()
  }
  window.removeEventListener('resize', handleResize)
})
</script>

<template>
  <div class="echarts-container">
    <!-- 上方饼图 -->
    <div class="top-section">
      <div ref="pieChartContainer" class="chart-container"></div>
    </div>

    <!-- 下方柱状图 -->
    <div class="bottom-section">
      <div ref="barChartContainer" class="chart-container"></div>
    </div>
  </div>
</template>

<style scoped>
.echarts-container {
  padding: 20px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column; /* 垂直排列 */
}

.top-section {
  margin-bottom: 20px; /* 上下间距 */
}

.chart-container {
  width: 100%;
  height: 400px;
}

.bottom-section {
  background: #fafafa;
  border-radius: 8px; /* 圆角 */
  padding: 10px;
}
</style>