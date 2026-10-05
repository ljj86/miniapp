<template>
  <div style="display: flex; position: relative;">
    <div class="box"
         :style="minImgBoxStyle"
         @mouseleave="mouseLeave"
         @mouseenter="mouseEnter"
         @mousemove="mousemove">
      <img :style="minImgStyle" fit="contain" ref="minImgRef" :src="finalMinIMGsrc"/>
      <div v-show="show" class="areaMark" :style="areaMarkStyle"></div>
    </div>
    <div class="box maxImgBox" :style="maxImgBoxStyle" v-show="show">
      <img :style="maxImgStyle" fit="contain" :src="finalMaxIMGsrc"/>
    </div>
  </div>
</template>

<script>
import { defineComponent, ref, reactive, onMounted, watch } from 'vue';

export default defineComponent({
  props: {
    minIMGsrc: String,
    maxIMGsrc: String,
    scale: {
      type: Number,
      default: 2
    },
    width: {
      type: Number,
      default: 500
    },
    height: {
      type: Number,
      default: 500
    },
  },
  setup(props) {
    const show = ref(false);
    const finalMinIMGsrc = ref('');
    const finalMaxIMGsrc = ref('');
    const imgBoxWidth = ref(0);
    const imgBoxHeight = ref(0);
    const areaWidth = ref(props.width / props.scale); // Updated calculation
    const areaHeight = ref(props.height / props.scale); // Updated calculation
    const areaMarkStyle = reactive({});
    const minImgBoxStyle = reactive({cursor: 'move'});
    const minImgStyle = reactive({});
    const maxImgBoxStyle = reactive({backgroundColor: '#ffffff'});
    const maxImgStyle = reactive({position: 'absolute'});
    const minImgRef = ref(null);

    const init = () => {
      imgBoxWidth.value = props.width;
      imgBoxHeight.value = props.height;
      minImgStyle.width = imgBoxWidth.value + 'px';
      minImgStyle.height = imgBoxHeight.value + 'px';
      maxImgStyle.width = imgBoxWidth.value + 'px';
      maxImgStyle.height = imgBoxHeight.value + 'px';
      minImgBoxStyle.width = imgBoxWidth.value + 'px';
      minImgBoxStyle.height = imgBoxHeight.value + 'px';
      maxImgBoxStyle.width = imgBoxWidth.value + 'px';
      maxImgBoxStyle.height = imgBoxHeight.value + 'px';
      maxImgBoxStyle.left = imgBoxWidth.value + 'px';
      finalMinIMGsrc.value = props.minIMGsrc;
      finalMaxIMGsrc.value = props.maxIMGsrc || props.minIMGsrc;
      areaMarkStyle.width = areaWidth.value + 'px';
      areaMarkStyle.height = areaHeight.value + 'px';
      maxImgStyle.transform = 'scale(' + props.scale + ')';
    };

    const mouseEnter = () => {
      show.value = true;
    };

    const mouseLeave = () => {
      show.value = false;
    };

    const mousemove = (e) => {
      if (!minImgRef.value) return;

      const documentScrollTop = document.documentElement.scrollTop || document.body.scrollTop;
      const mouseClientX = e.clientX;
      const mouseClientY = e.clientY;
      const minImgPosition = minImgRef.value.getBoundingClientRect();
      const minImgX = minImgPosition.left;
      const minImgY = minImgPosition.top;

      let areaLeft = mouseClientX - minImgX - areaWidth.value / 2;
      let areaTop = mouseClientY - minImgY - areaHeight.value / 2;

      if (documentScrollTop > 0) {
        areaTop += documentScrollTop;
      }

      const minLeft = 0;
      const maxLeft = imgBoxWidth.value - areaWidth.value;
      const minTop = 0;
      const maxTop = imgBoxHeight.value - areaHeight.value;

      areaLeft = Math.max(minLeft, Math.min(maxLeft, areaLeft));
      areaTop = Math.max(minTop, Math.min(maxTop, areaTop));

      areaMarkStyle.left = areaLeft + 'px';
      areaMarkStyle.top = areaTop + 'px';
      maxImgStyle.left = (props.scale - 1) * imgBoxWidth.value / 2 - areaLeft * props.scale + 'px';
      maxImgStyle.top = (props.scale - 1) * imgBoxHeight.value / 2 - areaTop * props.scale + 'px';
    };

    watch(() => props.minIMGsrc, init);
    watch(() => props.maxIMGsrc, init);

    onMounted(init);

    return {
      show,
      finalMinIMGsrc,
      finalMaxIMGsrc,
      minImgBoxStyle,
      minImgStyle,
      maxImgBoxStyle,
      maxImgStyle,
      areaMarkStyle,
      mouseEnter,
      mouseLeave,
      mousemove,
      minImgRef
    };
  }
});
</script>

<style scoped>
.box {
  position: relative;
  overflow: hidden;
  box-sizing: border-box;
  border: 1px solid #ccc;
  border-radius: 10px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
}

.areaMark {
  position: absolute;
  background: rgba(65, 116, 182, 0.3);
  border-radius: 4px;
  pointer-events: none;
}

.maxImgBox {
  position: absolute;
  z-index: 9999;
  border: 1px solid rgba(29, 55, 86, 0.29);
  border-radius: 8px;
  overflow: hidden;
}
</style>