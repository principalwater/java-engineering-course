package dev.principalwater.study.bank.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
class JwtTokenRelayGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {
    JwtTokenRelayGatewayFilterFactory() {
        super(Object.class);
    }

    @Override
    public GatewayFilter apply(Object ignored) {
        return (exchange, chain) -> exchange.getPrincipal().cast(JwtAuthenticationToken.class)
                .flatMap(authentication -> chain.filter(exchange.mutate().request(request ->
                        request.headers(headers -> headers.setBearerAuth(authentication.getToken().getTokenValue()))).build()));
    }
}
