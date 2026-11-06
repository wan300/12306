<template>
  <div class="account-page">
    <p class="account-intro">
      分别使用对应平台 App 扫码，登录后即可查询和管理任务。
    </p>
    <div class="account-grid">
      <FlightLoginCard provider="ctrip" /><FlightLoginCard
        provider="qunar"
      /><RailLoginCard />
    </div>
  </div>
</template>
<script setup>
import FlightLoginCard from '../components/FlightLoginCard.vue'
import RailLoginCard from '../components/RailLoginCard.vue'
</script>
<style scoped>
.account-page {
  max-width: 1360px;
  margin: auto;
}
.account-intro {
  color: #738295;
  margin-bottom: 20px;
}
.account-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
  align-items: start;
}
@media (max-width: 1000px) {
  .account-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 680px) {
  .account-grid {
    grid-template-columns: 1fr;
  }
}
</style>
