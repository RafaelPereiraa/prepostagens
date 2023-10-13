package io.t3w.correios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.t3w.correios.prazo.T3WCorreiosPrazo;
import io.t3w.correios.preco.T3WCorreiosPreco;
import io.t3w.correios.rastreamento.T3WCorreiosSroObjeto;

import javax.net.ssl.HttpsURLConnection;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Classe responsavel por concentrar as opções de serviços dos Correios.
 */
public class T3WCorreios implements T3WLoggable {

    private Duration timeout;
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final String userId, apiToken, cartaoPostagem;
    private T3WCorreiosBearerToken bearerToken;

    public T3WCorreios(final String userId, final String apiToken, final String cartaoPostagem) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Um ID de usuário válido é necessário para a consulta!");
        }
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("Um token de API válido é necessário para a consulta!");
        }
        if (cartaoPostagem == null || cartaoPostagem.isBlank()) {
            throw new IllegalArgumentException("Um catão de postagem válido é necessário para a consulta!");
        }

        this.userId = userId;
        this.apiToken = apiToken;
        this.cartaoPostagem = cartaoPostagem;

        this.timeout = Duration.ofSeconds(15);
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).followRedirects(HttpClient.Redirect.NORMAL).build();
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public T3WCorreios setTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    T3WCorreiosBearerToken requestBearerToken() throws Exception {
        if (this.bearerToken == null || this.bearerToken.getExpiraEm().isBefore(LocalDateTime.now())) {
            this.getLogger().debug("Requisitando novo bearer token para usuario '{}', api token '{}' e cartão de postagem '{}'", this.userId, this.apiToken, this.cartaoPostagem);
            final var httpRequest = HttpRequest.newBuilder()
                    .uri(new URI("https://api.correios.com.br/token/v1/autentica/cartaopostagem"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"numero\":\"%s\"}".formatted(cartaoPostagem)))
                    .headers("Content-Type", "application/json; charset=utf-8", "Authorization", ("Basic %s".formatted(Base64.getEncoder().encodeToString("%s:%s".formatted(userId, apiToken).getBytes(StandardCharsets.UTF_8)))))
                    .timeout(this.timeout)
                    .build();
            final var response = this.client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == HttpURLConnection.HTTP_CREATED) {
                this.bearerToken = this.objectMapper.readValue(response.body(), T3WCorreiosBearerToken.class);
            } else {
                throw new IllegalAccessException("Requisição de bearer token retornou codigo '%s': '%s'".formatted(response.statusCode(), response.body()));
            }
        }
        return bearerToken;
    }

    public List<T3WCorreiosSroObjeto> rastrearObjetos(final Set<String> codigosObjetos) throws Exception {
        this.getLogger().debug("Rastreando objetos para usuario '{}', api token '{}' e cartão de postagem '{}': '{}'", this.userId, this.apiToken, this.cartaoPostagem, codigosObjetos);
        final var url = new URI("https://api.correios.com.br/srorastro/v1/objetos?codigosObjetos=%s&resultado=T".formatted(String.join(",", codigosObjetos)));
        final var response = this.sendGetRequest(url);
        if (response.statusCode() == HttpsURLConnection.HTTP_OK) {
            return Arrays.asList(this.objectMapper.convertValue(this.objectMapper.readTree(response.body()).get("objetos"), T3WCorreiosSroObjeto[].class));
        }
        throw new Exception("Requisição de rastreamento de objetos retornou código '%s': '%s'".formatted(response.statusCode(), response.body()));
    }

    public T3WCorreiosPrazo calcularPrazo(final String codigoServico, final String cepOrigem, final String cepDestino) throws Exception {
        this.getLogger().debug("Solicitando prazo de entrega para o servico '{}' de '{}' para '{}'...", codigoServico, cepOrigem, cepDestino);
        final var url = new URI("https://api.correios.com.br/prazo/v1/nacional/%s?cepOrigem=%s&cepDestino=%s".formatted(codigoServico, cepOrigem, cepDestino));
        final var response = this.sendGetRequest(url);
        if (response.statusCode() == HttpsURLConnection.HTTP_OK) {
            return this.objectMapper.readValue(response.body(), T3WCorreiosPrazo.class);
        }
        throw new Exception("Verificação de prazo de entrega para servico '%s' retornou código '%s': '%s'".formatted(codigoServico, response.statusCode(), response.body()));
    }

    public T3WCorreiosPreco calcularPreco(final String codigoServico, final String cepOrigem, String cepDestino, double pesoEmKg, int formato, int comprimentoEmCm, int alturaEmCm, int larguraEmCm, int diametroEmCm, boolean maoPropria, double valorDeclarado, boolean avisoRecebimento) throws Exception {
        this.getLogger().debug("Solicitando preço para envio de objeto...");

        //TODO: verificar se existe enum dos servicos adicionais e passar isso como uma lista para este metodo
        final var servicosAdicionaisParameter = new StringBuilder();
        if (maoPropria || avisoRecebimento || valorDeclarado != 0) {
            servicosAdicionaisParameter.append("&servicosAdicionais=");
            servicosAdicionaisParameter.append(avisoRecebimento ? "001" : "");
            servicosAdicionaisParameter.append(maoPropria ? (avisoRecebimento ? ",002" : "002") : "");
            servicosAdicionaisParameter.append(valorDeclarado != 0 ? (maoPropria || avisoRecebimento ? ",019" : "019") : "");
        }

        //TODO: passar o peso para gramas, tirar o valor declarado do hardcoded, padronizar adicao de parametros a url
        final var url = new URI((("https://api.correios.com.br/preco/v1/nacional/%s?cepDestino=%s&cepOrigem=%s&psObjeto=%s&tpObjeto=%s&comprimento=%s&largura=%s&altura=%s&diametro=%s&vlDeclarado=33.33" + servicosAdicionaisParameter).formatted(codigoServico, cepDestino, cepOrigem, pesoEmKg * 1000, formato, comprimentoEmCm, alturaEmCm, larguraEmCm, diametroEmCm, valorDeclarado)));
        final var response = this.sendGetRequest(url);
        if (response.statusCode() == HttpsURLConnection.HTTP_OK) {
            //TODO: analisar json de retorno e parsear os valores de forma correta
            return this.objectMapper.readValue(response.body().replaceAll("(\"\\d+),(\\d+\")", "$1.$2"), T3WCorreiosPreco.class);
        }
        throw new Exception("Verificação de preco de envio retornou código '%s': '%s'".formatted(response.statusCode(), response.body()));

    }

    private HttpResponse<String> sendGetRequest(final URI url) throws Exception {
        final var bearerToken = this.requestBearerToken().getToken();
        final var httpRequest = HttpRequest.newBuilder()
                .uri(url)
                .GET()
                .headers("Content-Type", "application/json; charset=utf-8", "Authorization", ("Bearer %s".formatted(bearerToken)))
                .timeout(this.timeout)
                .build();
        return this.client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }
}
