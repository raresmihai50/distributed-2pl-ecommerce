package ro.distr2pl.app.dto;

import java.math.BigDecimal;

public class UpdatePriceRequest {
    private BigDecimal newPrice;

    public UpdatePriceRequest() {
    }

    public UpdatePriceRequest(BigDecimal newPrice) {
        this.newPrice = newPrice;
    }

    public BigDecimal getNewPrice() {
        return newPrice;
    }

    public void setNewPrice(BigDecimal newPrice) {
        this.newPrice = newPrice;
    }
}

