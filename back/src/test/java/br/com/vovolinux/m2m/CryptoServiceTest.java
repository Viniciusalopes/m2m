package br.com.vovolinux.m2m;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.vovolinux.m2m.config.AppProperties;
import br.com.vovolinux.m2m.crypto.CryptoService;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private static CryptoService servico(String chave) {
        CryptoService s = new CryptoService(new AppProperties(new AppProperties.Admin("a", "b", "c"), chave));
        try {
            var init = CryptoService.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(s);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    @Test
    void cifraEDecifraIdaEVolta() {
        CryptoService s = servico("uma-chave-mestra-bem-comprida");
        byte[] cifrado = s.encrypt("senha-secreta-çã");
        assertThat(new String(cifrado)).doesNotContain("senha-secreta");
        assertThat(s.decryptString(cifrado)).isEqualTo("senha-secreta-çã");
    }

    @Test
    void mesmoTextoGeraCifradosDiferentes() {
        CryptoService s = servico("uma-chave-mestra-bem-comprida");
        assertThat(s.encrypt("x")).isNotEqualTo(s.encrypt("x"));
    }

    @Test
    void chaveDiferenteNaoDecifra() {
        byte[] cifrado = servico("uma-chave-mestra-bem-comprida").encrypt("segredo");
        assertThatThrownBy(() -> servico("outra-chave-mestra-bem-comprida").decrypt(cifrado))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adulteracaoEhDetectada() {
        CryptoService s = servico("uma-chave-mestra-bem-comprida");
        byte[] cifrado = s.encrypt("segredo");
        cifrado[cifrado.length - 1] ^= 1;
        assertThatThrownBy(() -> s.decrypt(cifrado)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void chaveAusenteOuCurtaImpedeAInicializacao() {
        assertThatThrownBy(() -> servico("")).hasMessageContaining("APP_SECRET_KEY");
        assertThatThrownBy(() -> servico("curta")).hasMessageContaining("APP_SECRET_KEY");
    }
}
