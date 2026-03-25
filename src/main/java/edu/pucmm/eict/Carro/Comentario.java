package edu.pucmm.eict.Carro;

import jakarta.persistence.*;

@Entity
public class Comentario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String autor;

    @Column(length = 2000)
    private String texto;

    @ManyToOne
    private Producto producto;

    public Comentario() {
    }

    public Comentario(String autor, String texto, Producto producto) {
        this.autor = autor;
        this.texto = texto;
        this.producto = producto;
    }

    public Long getId() {
        return id;
    }

    public String getAutor() {
        return autor;
    }

    public String getTexto() {
        return texto;
    }

    public Producto getProducto() {
        return producto;
    }
}