import { expect, Page, test } from '@playwright/test';

const ADMIN_EMAIL = 'admin@stockrush.demo';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? 'demo-admin-123';

async function signIn(page: Page, email: string, password: string) {
  await page.goto('/entrar');
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('Senha').fill(password);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('link', { name: 'Catálogo' })).toBeVisible();
}

test('the admin fires the race and the result adds up', async ({ page }) => {
  const buyers = 120;
  const stock = 30;

  await signIn(page, ADMIN_EMAIL, ADMIN_PASSWORD);
  await page.getByRole('link', { name: 'Painel' }).click();

  await page.getByLabel('Compradores simultâneos').fill(String(buyers));
  await page.getByLabel('Estoque inicial (opcional)').fill(String(stock));
  await page.getByRole('button', { name: 'Disparar disputa' }).click();

  const summary = page.locator('.summary');
  await expect(summary).toContainText('vendidos');
  const [, sold, rejected, finalStock] = (await summary.innerText()).match(
    /(\d+) vendidos, (\d+) rejeitados, estoque final (\d+)/,
  )!.map(Number);

  expect(sold + rejected).toBe(buyers);
  expect(sold).toBe(stock);
  expect(finalStock).toBe(stock - sold);
  await expect(page.locator('app-result-chart canvas')).toBeVisible();
});

test('a regular customer does not get the panel', async ({ page }) => {
  await page.goto('/entrar');
  await page.getByRole('button', { name: 'Criar conta' }).click();
  await page.getByLabel('E-mail').fill(`e2e-${Date.now()}@example.com`);
  await page.getByLabel('Senha').fill('a-long-enough-password');
  await page.getByRole('button', { name: 'Criar e entrar' }).click();

  await expect(page.getByRole('link', { name: 'Catálogo' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Painel' })).toHaveCount(0);

  await page.goto('/painel');
  await expect(page.getByRole('heading', { name: 'Painel de demonstração' })).toHaveCount(0);
});
