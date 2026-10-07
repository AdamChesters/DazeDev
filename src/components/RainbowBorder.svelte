<div class="rainbow-border" aria-hidden="true">
  <div class="edge top"><i></i></div>
  <div class="edge right"><i></i></div>
  <div class="edge bottom"><i></i></div>
  <div class="edge left"><i></i></div>
</div>

<style>
  .rainbow-border {
    position: absolute;
    inset: 0;
    border-radius: inherit;
    overflow: hidden;
    pointer-events: none;
    z-index: 8;
    box-shadow: 0 0 7px #8080ff66;
    animation: breathe 2.4s ease-in-out infinite;
  }
  .edge {
    position: absolute;
    overflow: hidden;
  }
  .top,
  .bottom {
    left: 0;
    width: 100%;
    height: 3px;
  }
  .top {
    top: 0;
  }
  .bottom {
    bottom: 0;
  }
  .left,
  .right {
    top: 0;
    height: 100%;
    width: 3px;
  }
  .left {
    left: 0;
  }
  .right {
    right: 0;
  }
  i {
    display: block;
    will-change: transform;
    background: linear-gradient(
      90deg,
      #ff58d6,
      #965cff,
      #428bff,
      #44ecff,
      #55f4a5,
      #f9ed65,
      #ff9951,
      #ff58d6
    );
    background-size: 50% 100%;
    width: 200%;
    height: 100%;
    animation: horizontal 3.2s linear infinite;
  }
  .left i,
  .right i {
    width: 100%;
    height: 200%;
    background: linear-gradient(
      0deg,
      #ff58d6,
      #965cff,
      #428bff,
      #44ecff,
      #55f4a5,
      #f9ed65,
      #ff9951,
      #ff58d6
    );
    background-size: 100% 50%;
    animation-name: vertical;
  }
  .bottom i,
  .left i {
    animation-direction: reverse;
  }
  @keyframes horizontal {
    to {
      transform: translate3d(-50%, 0, 0);
    }
  }
  @keyframes vertical {
    to {
      transform: translate3d(0, -50%, 0);
    }
  }
  @keyframes breathe {
    0%,
    100% {
      opacity: 0.55;
    }
    50% {
      opacity: 1;
    }
  }
  :global(html[data-highlight-motion='static']) .rainbow-border,
  :global(html[data-highlight-motion='static']) i {
    animation: none;
  }
</style>
