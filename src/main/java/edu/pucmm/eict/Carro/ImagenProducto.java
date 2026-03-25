package edu.pucmm.eict.Carro;

import jakarta.persistence.*;

@Entity
public class ImagenProducto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 10000000, columnDefinition = "CLOB")
    private String base64;

    @ManyToOne
    private Producto producto;

    public ImagenProducto() {
    }

    public ImagenProducto(String base64, Producto producto) {
        this.base64 = base64;
        this.producto = producto;
    }

    public Long getId() {
        return id;
    }

    public String getBase64() {
        return base64;
    }

    public void setBase64(String base64) {
        this.base64 = base64;
    }

    public void setProducto(Producto p) {
        this.producto = p;
    }
}