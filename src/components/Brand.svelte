<script lang="ts">
  let { large = false }: { large?: boolean } = $props();
</script>

<div class="brand" class:large>
  <img src="/brand.svg" alt="" width={large ? 84 : 28} height={large ? 84 : 28} />
  <span class="brand-wordmark"><strong>daze</strong>dev<span class="brand-dot">.</span></span>
</div>

<style>
  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .brand-wordmark {
    font-size: 24px;
    font-weight: 400;
    letter-spacing: -1px;
  }
  .brand-wordmark strong {
    font-weight: 750;
  }
  .brand-dot {
    color: #39bdd7;
  }
  .large {
    flex-direction: column;
    gap: 18px;
  }
  .large .brand-wordmark {
    font-size: 42px;
  }
</style>
