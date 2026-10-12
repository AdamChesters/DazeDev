<script lang="ts">
  import type { Snippet } from 'svelte';
  import { content, manifest, artwork, appText } from '../adapter';

  let {
    appName,
    appId,
    appLogo,
    appVersion,
    status,
    updateState = 'unchecked',
    updateAvailable = '',
    updateBusy = false,
    updateSupported = false,
    checkForUpdates,
    onfeedback,
    openExternal,
    glow = true,
    onglowchange,
  }: {
    appName: string;
    appId: string;
    appLogo: Snippet;
    appVersion: string;
    status: string;
    updateState?: 'unchecked' | 'checking' | 'current' | 'available' | 'error' | 'installing';
    updateAvailable?: string;
    updateBusy?: boolean;
    updateSupported?: boolean;
    checkForUpdates: () => void;
    onfeedback: () => void;
    openExternal: (url: string) => Promise<void>;
    glow?: boolean;
    onglowchange: (enabled: boolean) => void;
  } = $props();
  let message = $state('');
  async function visit(url: string) {
    message = '';
    try {
      await openExternal(url);
    } catch {
      message = 'Could not open the link. Please try again.';
    }
  }
</script>

<div class="support-dialog" data-app={appId} data-component-version={manifest.version}>
  <header>
    {@render appLogo()}
    <p class="support-version-row" role="status">
      <span class="support-version">
        <i
          class="update-dot"
          data-state={updateSupported ? updateState : 'unchecked'}
          aria-hidden="true"
        ></i>
        <strong>Version {appVersion}</strong>
      </span>
      <span class="support-status">{status}</span>
    </p>
    <div class="support-actions">
      <button class="support-discord" onclick={() => void visit(content.links.discord)}>
        {content.actions.discord}
      </button>
      <button disabled={updateBusy || !updateSupported} onclick={checkForUpdates}
        >{updateAvailable ? `Update to ${updateAvailable}` : content.actions.update}</button
      >
      <button onclick={onfeedback}>{content.actions.feedback}</button>
    </div>
  </header>
  <div class="support-intro">
    <p>{appText(content.intro, appName)}</p>
    <p class="support-projects">
      {content.projectsText}
      <a
        href={content.links.github}
        target="_blank"
        rel="noopener noreferrer"
        onclick={(e) => {
          e.preventDefault();
          void visit(e.currentTarget.href);
        }}>GitHub</a
      >
      <span aria-hidden="true"> | </span>
      <a
        href={content.links.website}
        target="_blank"
        rel="noopener noreferrer"
        onclick={(e) => {
          e.preventDefault();
          void visit(e.currentTarget.href);
        }}>AdamCh.com</a
      >
    </p>
  </div>
  <section aria-label={content.donationTitle} class="support-donations">
    <h3>{content.donationTitle}</h3>
    <p>{content.donationText}</p>
    <div class="support-buttons">
      {#each content.platforms as platform (platform.id)}
        <div class="donation-option">
          <button
            class="donation-button"
            class:github-button={platform.id === 'github'}
            class:coffee-button={platform.id === 'buymeacoffee'}
            class:paypal-button={platform.id === 'paypal'}
            onclick={() => void visit(platform.url)}
          >
            {#if platform.id === 'paypal'}
              <span
                class="paypal-mark"
                style:--paypal-mark={`url("${artwork[platform.icon]}")`}
                aria-hidden="true"
              ></span>
              <span
                >{platform.label.slice(0, -6)}<strong class="paypal-wordmark"
                  >Pay<span>Pal</span></strong
                ></span
              >
            {:else}
              <img src={artwork[platform.icon]} alt="" aria-hidden="true" />
              <span>{platform.label}</span>
            {/if}
          </button>
          <div class="donation-notes">
            {#each platform.notes as note}
              <span
                class="checkout-note"
                title={note === '0% fees to creator'
                  ? 'GitHub personal-account sponsorships have no platform fees'
                  : undefined}>{note}</span
              >
            {/each}
          </div>
        </div>
      {/each}
    </div>

    {#if message}<p class="support-message" role="status">{message}</p>{/if}
  </section>
  <label class="support-glow-toggle">
    <input
      type="checkbox"
      checked={!glow}
      onchange={(e) => onglowchange(!e.currentTarget.checked)}
    />
    stop glowing at me you silly goose
  </label>
</div>

<style>
  .support-dialog {
    display: flex;
    flex-direction: column;
    gap: 24px;
    line-height: 1.6;
  }
  .support-dialog p {
    margin: 0;
  }
  .support-intro {
    display: grid;
    gap: 12px;
  }
  header {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 12px;
    text-align: center;
    margin: 0;
  }
  header :global(.brand) {
    align-items: center;
    gap: 6px;
  }
  .support-version-row {
    display: flex;
    align-items: center;
    justify-content: center;
    flex-wrap: wrap;
    gap: 8px;
    margin: 0;
  }
  .support-version {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }
  .support-version .update-dot {
    flex-shrink: 0;
  }
  .support-status {
    color: var(--muted);
    overflow-wrap: anywhere;
  }
  header button {
    max-width: 100%;
    white-space: normal;
    overflow-wrap: anywhere;
  }
  .support-actions {
    display: flex;
    justify-content: center;
    flex-wrap: wrap;
    gap: 10px;
  }
  .support-actions button {
    min-height: 44px;
  }
  .support-actions .support-discord {
    background: #5865f2;
    color: #fff;
    border-color: #5865f2;
  }
  .support-actions .support-discord:hover {
    background: #4752c4;
    border-color: #4752c4;
  }
  .support-dialog .support-glow-toggle {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 44px;
    margin: 0;
    justify-content: flex-start;
    flex-direction: row;
    text-align: left;
    width: fit-content;
  }
  .checkout-note {
    font-size: 13px;
    color: var(--muted);
  }
  .support-projects a {
    display: inline;
  }
  a {
    color: var(--accent);
    width: fit-content;
    overflow-wrap: anywhere;
  }
  .support-donations {
    display: grid;
    gap: 16px;
    margin: 0;
    padding-top: 24px;
    border-top: 1px solid var(--border);
  }
  .support-donations h3 {
    display: block;
    text-align: center;
    padding: 0;
    font-size: 22px;
    line-height: 1.3;
    margin: 0;
  }
  .support-buttons {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
  }
  .donation-option {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
    text-align: center;
  }
  .donation-notes {
    display: grid;
    gap: 4px;
  }
  .support-buttons .donation-button {
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-width: 0;
    min-height: 64px;
    padding: 10px;
    border-radius: 8px;
    font-size: 14px;
    font-weight: 600;
    line-height: 1.3;
    white-space: normal;
    overflow-wrap: anywhere;
    border: 1px solid transparent;
  }
  .donation-button img,
  .paypal-mark {
    width: 25px;
    height: 28px;
    object-fit: contain;
    flex-shrink: 0;
  }
  .support-buttons .github-button {
    color: #fff;
    background: #24292f;
    border-color: #57606a;
  }
  .support-buttons .coffee-button {
    color: #111;
    background: #ffdd00;
    border-color: #e1c400;
  }
  .support-buttons .paypal-button {
    color: #003087;
    background: #ffc439;
    border-color: #e4ad25;
    border-radius: 24px;
  }
  .paypal-mark {
    background: #003087;
    mask: var(--paypal-mark) center / contain no-repeat;
  }
  .paypal-wordmark {
    font-size: 17px;
    font-style: italic;
    white-space: nowrap;
  }
  .paypal-wordmark span {
    color: #0070ba;
  }
  .support-buttons .donation-button:hover {
    filter: brightness(0.93);
  }
  .support-buttons .donation-button:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 3px;
  }
  .support-message {
    color: var(--muted);
  }
  @media (max-width: 480px) {
    .support-buttons {
      grid-template-columns: 1fr;
    }
  }
</style>
