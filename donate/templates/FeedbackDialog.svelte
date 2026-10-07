<script lang="ts">
  import { Send } from '@lucide/svelte';
  import { content, appText } from '../adapter';
  let {
    appName,
    name = $bindable(''),
    email = $bindable(''),
    message = $bindable(''),
    busy,
    sent,
    error,
    oncontactchange,
    onmessagechange,
    onsubmit,
    onclose,
  }: {
    appName: string;
    name: string;
    email: string;
    message: string;
    busy: boolean;
    sent: boolean;
    error: string;
    oncontactchange: () => void;
    onmessagechange: () => void;
    onsubmit: () => void;
    onclose: () => void;
  } = $props();
</script>

<p class="hint">{appText(content.feedback.intro, appName)}</p>
<form
  onsubmit={(e) => {
    e.preventDefault();
    onsubmit();
  }}
>
  <fieldset class="feedback-fields" disabled={busy}>
    <div class="feedback-contact">
      <label
        >{content.feedback.fields[0].label}<input
          name="name"
          autocomplete="name"
          required
          maxlength={content.feedback.fields[0].maxLength}
          bind:value={name}
          oninput={oncontactchange}
        /></label
      >
      <label
        >{content.feedback.fields[1].label}<input
          name="email"
          type="email"
          autocomplete="email"
          required
          maxlength={content.feedback.fields[1].maxLength}
          bind:value={email}
          oninput={oncontactchange}
        /></label
      >
    </div>
    <p class="hint">Your name and email are remembered in this portable copy.</p>
    <label
      >{content.feedback.fields[2].label}<textarea
        name="message"
        required
        maxlength={content.feedback.fields[2].maxLength}
        rows="5"
        placeholder={content.feedback.placeholder}
        bind:value={message}
        oninput={onmessagechange}></textarea></label
    >
  </fieldset>
  <p class="hint">
    {content.feedback.privacy}
  </p>
  {#if error}<p class="inline-error" role="alert">
      {error} Your message is still here.
    </p>{/if}
  <p role="status">
    {busy ? 'Sending…' : sent ? content.feedback.success : ''}
  </p>
  <div class="form-row">
    <button type="button" onclick={onclose}>Close</button>
    <button class="primary" type="submit" disabled={busy || !message.trim()}>
      <Send size={16} aria-hidden="true" />{busy ? 'Sending…' : content.feedback.submit}
    </button>
  </div>
</form>

<style>
  .feedback-fields {
    border: 0;
    padding: 0;
    margin: 0;
    min-width: 0;
  }
  .feedback-contact {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 16px;
  }
  .feedback-fields textarea {
    min-height: 120px;
  }
  @media (max-width: 520px) {
    .feedback-contact {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
