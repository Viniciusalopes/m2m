import { expect, test, Page } from "@playwright/test";
import { execSync } from "node:child_process";
import { mkdirSync, rmSync } from "node:fs";
import { credenciais } from "../playwright.config";
import { SANDBOX } from "../ambiente";

// Precisa do compose de teste (OpenSSH em container) e grava só no sandbox e nele: E2E_SERVERS=1
const comServidores = process.env.E2E_SERVERS === "1";
const sshHost = process.env.E2E_SSH_HOST ?? "m2m-test-ssh";

async function entrar(page: Page) {
  await page.goto("/login");
  await page.getByLabel("Usuário").fill(credenciais.usuario);
  await page.getByLabel("Senha", { exact: true }).fill(credenciais.senha);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page).toHaveURL(/\/$/);
}

/** Chamadas à API com a sessão do navegador (o CSRF vem do cookie, como no front). */
function api(page: Page) {
  const cabecalho = async () => {
    const c = (await page.context().cookies()).find(
      (x) => x.name === "XSRF-TOKEN",
    );
    return { "X-XSRF-TOKEN": c?.value ?? "" };
  };
  return {
    async post<T>(url: string, corpo?: unknown): Promise<T> {
      const r = await page.request.post(url, {
        data: corpo ?? null,
        headers: await cabecalho(),
      });
      expect(r.status(), `${url}: ${await r.text()}`).toBeLessThan(300);
      return (r.status() === 204 ? undefined : await r.json()) as T;
    },
    async get<T>(url: string): Promise<T> {
      const r = await page.request.get(url);
      expect(r.status(), url).toBe(200);
      return (await r.json()) as T;
    },
    async del(url: string): Promise<void> {
      const r = await page.request.delete(url, { headers: await cabecalho() });
      expect(r.status(), url).toBeLessThan(300);
    },
  };
}

test.describe('emergência e "Parar tudo" em operações grandes', () => {
  const id = Date.now();
  const raiz = `${SANDBOX}/e2e-emerg-${id}`;
  const nome = `E2E-EMERG-${id}`;

  test.beforeAll(() => {
    mkdirSync(`${raiz}/origem`, { recursive: true });
    // dados incompressíveis: o backup e a transferência demoram o bastante para dar tempo de interromper
    for (let i = 0; i < 4; i++) {
      execSync(`head -c 150M /dev/urandom > ${raiz}/origem/g${i}.bin`);
    }
  });

  test.afterAll(() => rmSync(raiz, { recursive: true, force: true }));

  async function maquinaSsh(page: Page): Promise<number> {
    const a = api(page);
    const m = await a.post<{ id: number }>("/api/machines", {
      name: nome,
      host: sshHost,
      protocol: "SFTP",
      port: 2222,
      username: "m2m",
      authType: "PASSWORD",
      password: "m2m-teste",
    });
    const teste = await a.post<{ fingerprint: string }>(
      `/api/machines/${m.id}/test`,
    );
    await a.post(`/api/machines/${m.id}/identity/confirm`, {
      fingerprint: teste.fingerprint,
    });
    await a.post(`/api/machines/${m.id}/test`);
    return m.id;
  }

  test("o botão INTERROMPER AGORA para o backup e não deixa arquivo parcial no armazenamento", async ({
    page,
  }) => {
    test.skip(!credenciais.senha, "ADMIN_PASSWORD não definida");
    test.skip(!comServidores, "requer o compose de teste (E2E_SERVERS=1)");
    test.setTimeout(150_000);
    await entrar(page);
    const a = api(page);
    const ssh = await maquinaSsh(page);
    const local = (
      await a.get<{ id: number; protocol: string }[]>("/api/machines")
    ).find((m) => m.protocol === "LOCAL")!;
    let perfil: number | null = null;
    try {
      const p = await a.post<{ id: number }>("/api/profiles", {
        name: nome,
        complement: null,
        sourceMachineId: local.id,
        directories: [{ path: `${raiz}/origem`, mappings: [] }],
        storage: { machineId: ssh, path: `/config/emerg-${id}` },
        suspectThreshold: 0.5,
        encryptionEnabled: false,
        backupSchedule: null,
      });
      perfil = p.id;
      const job = await a.post<{ id: number }>(`/api/profiles/${p.id}/backup`, {
        complement: null,
      });
      await page.goto(`/jobs/${job.id}`);
      await page.getByRole("button", { name: "INTERROMPER AGORA" }).click();
      await expect(page.getByText("Interrompido").first()).toBeVisible({
        timeout: 30_000,
      });
      const final = await a.get<{ status: string }>(`/api/jobs/${job.id}`);
      expect(final.status).toBe("INTERRUPTED");
      // nenhum arquivo parcial (nem final) ficou no armazenamento remoto
      const navegar = await page.request.get(
        `/api/machines/${ssh}/browse?path=${encodeURIComponent(`/config/emerg-${id}`)}`,
      );
      if (navegar.status() === 200) {
        const itens = (await navegar.json()).items as { name: string }[];
        expect(
          itens.filter(
            (i) => i.name.endsWith(".partial") || i.name.endsWith(".tar.zst"),
          ),
        ).toHaveLength(0);
      }
    } finally {
      if (perfil) await a.del(`/api/profiles/${perfil}?keepBackups=false`);
      await a.del(`/api/machines/${ssh}`);
    }
  });

  test("Parar tudo cancela as transferências grandes e Retomar interrompidos as põe de volta na fila", async ({
    page,
  }) => {
    test.skip(!credenciais.senha, "ADMIN_PASSWORD não definida");
    test.skip(!comServidores, "requer o compose de teste (E2E_SERVERS=1)");
    test.setTimeout(150_000);
    await entrar(page);
    const a = api(page);
    const ssh = await maquinaSsh(page);
    try {
      await page.getByRole("link", { name: "Transferência" }).click();
      const esquerdo = page.locator("m2m-transfer-pane").nth(0);
      const direito = page.locator("m2m-transfer-pane").nth(1);
      await direito.getByRole("combobox").click({ force: true });
      await page.getByRole("option", { name: nome }).click();
      await esquerdo.getByLabel("Caminho").fill(raiz);
      await esquerdo.getByLabel("Caminho").press("Enter");
      await direito.getByLabel("Caminho").fill("/config");
      await direito.getByLabel("Caminho").press("Enter");
      await esquerdo.getByRole("row", { name: /origem/ }).click();
      await page
        .getByRole("button", {
          name: "Enviar a seleção do painel esquerdo para o direito",
        })
        .click();

      const fila = page.locator("m2m-transfer-queue");
      await expect(fila.getByText("Transferindo").first()).toBeVisible({
        timeout: 30_000,
      });
      await fila.getByRole("button", { name: "Parar tudo" }).click();
      await expect(fila.getByText("Cancelada").first()).toBeVisible({
        timeout: 30_000,
      });
      await expect(fila.getByText("Transferindo")).toHaveCount(0, {
        timeout: 30_000,
      });
      await expect(fila.getByText("Aguardando")).toHaveCount(0);

      // retomar: os cancelados voltam para a fila e voltam a transferir
      await fila.getByRole("button", { name: "Retomar interrompidos" }).click();
      await expect(fila.getByText("Transferindo").first()).toBeVisible({
        timeout: 30_000,
      });
      await fila.getByRole("button", { name: "Parar tudo" }).click();
      await expect(fila.getByText("Transferindo")).toHaveCount(0, {
        timeout: 30_000,
      });
    } finally {
      await page.request.post("/api/transfers/cancel-all", {
        headers: {
          "X-XSRF-TOKEN":
            (await page.context().cookies()).find(
              (x) => x.name === "XSRF-TOKEN",
            )?.value ?? "",
        },
      });
      await page.waitForTimeout(1500);
      await page.request.post("/api/transfers/fs/delete", {
        data: { machineId: ssh, path: "/config/origem", confirmed: true },
        headers: {
          "X-XSRF-TOKEN":
            (await page.context().cookies()).find(
              (x) => x.name === "XSRF-TOKEN",
            )?.value ?? "",
        },
      });
      await a.del(`/api/machines/${ssh}`);
    }
  });
});
