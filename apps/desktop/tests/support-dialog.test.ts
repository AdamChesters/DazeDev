// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { installDom } from './support/dom';

const openExternal = vi.hoisted(() => vi.fn(async (_url: string) => {}));
vi.mock('../src/lib/bridge', () => ({ desktop: true, openExternal }));

import SupportDialog from '../donate/SupportDialog.svelte';
import { artwork, content, manifest } from '../donate/adapter';

function dialog(overrides: Record<string, unknown> = {}) {
  const props = {
    version: '1.2.3',
    status: 'Up to date',
    updateState: 'current' as const,
    updateSupported: true,
    onupdate: vi.fn(),
    onfeedback: vi.fn(),
    onglowchange: vi.fn(),
    ...overrides,
  };
  return { ...render(SupportDialog, { props }), props };
}

beforeEach(() => {
  installDom();
  openExternal.mockClear();
  openExternal.mockImplementation(async () => {});
});

describe('support dialog', () => {
  it('uses the bundled 2.0.0 content with Support wording', () => {
    expect(manifest.version).toBe('2.0.0');
    expect(manifest.schemaVersion).toBe(2);
    expect(content.schemaVersion).toBe(2);
    expect(content.title).toBe('Feedback / Support');
    const { container } = dialog();
    expect(screen.getByRole('heading', { name: 'Support' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Support' })).toBeTruthy();
    expect(screen.getByText(content.donationText)).toBeTruthy();
    expect(screen.getByText(content.intro.replaceAll('{appName}', 'DazeDev'))).toBeTruthy();
    expect(container.textContent).not.toMatch(/donat/i);
    expect(container.textContent).toMatch(/Support with PayPal/);
    expect(container.querySelector('[data-component-version="2.0.0"]')).toBeTruthy();
  });

  it('shows the version, with the update and feedback buttons on one row', async () => {
    const { container, props } = dialog();
    expect(screen.getByText('Version 1.2.3')).toBeTruthy();
    const update = screen.getByRole('button', { name: content.actions.update });
    const feedback = screen.getByRole('button', { name: content.actions.feedback });
    expect(feedback.textContent).toBe('Feedback form');
    expect(update.parentElement).toBe(feedback.parentElement);
    expect(container.querySelector('.support-actions')?.children).toHaveLength(2);
    await fireEvent.click(update);
    expect(props.onupdate).toHaveBeenCalledOnce();
    await fireEvent.click(feedback);
    expect(props.onfeedback).toHaveBeenCalledOnce();
    expect(openExternal).not.toHaveBeenCalled();
  });

  it('offers the available update and disables the button while busy', async () => {
    const view = dialog({ updateAvailable: '1.3.0' });
    expect(screen.getByRole('button', { name: 'Update to 1.3.0' })).toBeTruthy();
    await view.rerender({ updateBusy: true });
    expect(
      (screen.getByRole('button', { name: 'Update to 1.3.0' }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('renders every configured social as an icon button in its brand colour', () => {
    const { container } = dialog();
    const row = container.querySelector('.support-socials')!;
    const buttons = [...row.querySelectorAll('button')];
    expect(buttons).toHaveLength(content.socials.length);
    content.socials.forEach((social, i) => {
      const button = buttons[i];
      expect(button.getAttribute('aria-label')).toBe(social.label);
      expect(button.title).toBe(social.label);
      expect(button.textContent?.trim()).toBe('');
      expect(button.style.background).not.toBe('');
      expect(button.querySelector('img')?.getAttribute('src')).toBe(artwork[social.icon]);
      expect(artwork[social.icon]).toBeTruthy();
    });
  });

  it('renders every configured support platform with its notes', () => {
    const { container } = dialog();
    const buttons = [...container.querySelectorAll('.support-buttons .donation-button')];
    expect(buttons).toHaveLength(content.platforms.length);
    content.platforms.forEach((platform, i) => {
      expect(buttons[i].textContent?.replace(/\s+/g, '')).toBe(platform.label.replace(/\s+/g, ''));
      expect(artwork[platform.icon]).toBeTruthy();
      for (const note of platform.notes)
        expect(screen.getAllByText(note).length).toBeGreaterThan(0);
    });
  });

  it('opens links only when a button is clicked', async () => {
    dialog();
    expect(openExternal).not.toHaveBeenCalled();
    for (const social of content.socials)
      await fireEvent.click(screen.getByRole('button', { name: social.label }));
    for (const platform of content.platforms)
      await fireEvent.click(
        screen.getByRole('button', { name: new RegExp(platform.label.replace(/\s+/g, '\\s*')) }),
      );
    expect(openExternal.mock.calls.map(([url]) => url)).toEqual([
      ...content.socials.map((s) => s.url),
      ...content.platforms.map((p) => p.url),
    ]);
  });

  it('says so when a link cannot be opened', async () => {
    openExternal.mockRejectedValueOnce(new Error('blocked'));
    dialog();
    await fireEvent.click(screen.getByRole('button', { name: content.socials[0].label }));
    await waitFor(() =>
      expect(screen.getByText('Could not open the link. Please try again.')).toBeTruthy(),
    );
  });
});
