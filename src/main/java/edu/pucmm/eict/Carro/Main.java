package edu.pucmm.eict.Carro;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import io.javalin.rendering.template.JavalinThymeleaf;
import io.javalin.websocket.WsContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.h2.tools.Server;
import org.jasypt.util.text.BasicTextEncryptor;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Main {

    // ─────────────────────────────────────────────────────────
    //  Req. 1 — Usuarios online
    // ─────────────────────────────────────────────────────────
    static final Set<WsContext> usuariosWs = ConcurrentHashMap.newKeySet();

    static void broadcastOnline() {
        String msg = "{\"online\":" + usuariosWs.size() + "}";
        usuariosWs.removeIf(ctx -> {
            try { ctx.send(msg); return false; }
            catch (Exception e) { return true; }
        });
    }

    // ─────────────────────────────────────────────────────────
    //  Req. 2 — Comentarios en tiempo real
    // ─────────────────────────────────────────────────────────
    static final Map<Long, Set<WsContext>> productosWs = new ConcurrentHashMap<>();

    static void broadcastEliminarComentario(Long productoId, Long comentarioId) {
        Set<WsContext> sesiones = productosWs.get(productoId);
        if (sesiones == null) return;
        String msg = "{\"tipo\":\"eliminarComentario\",\"comentarioId\":" + comentarioId + "}";
        sesiones.removeIf(ctx -> {
            try { ctx.send(msg); return false; }
            catch (Exception e) { return true; }
        });
    }

    static void broadcastAgregarComentario(Long productoId, Long comentarioId, String autor, String texto) {
        Set<WsContext> sesiones = productosWs.get(productoId);
        if (sesiones == null) return;
        String msg = "{\"tipo\":\"agregarComentario\",\"comentarioId\":" + comentarioId +
                ",\"autor\":\"" + escapeJson(autor) + "\",\"texto\":\"" + escapeJson(texto) + "\"}";
        sesiones.removeIf(ctx -> {
            try { ctx.send(msg); return false; }
            catch (Exception e) { return true; }
        });
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ─────────────────────────────────────────────────────────
    //  Req. 3 — Dashboard de ventas en tiempo real
    // ─────────────────────────────────────────────────────────
    static final Set<WsContext> dashboardWs = ConcurrentHashMap.newKeySet();

    static void broadcastDashboard(EntityManagerFactory emf) {
        if (dashboardWs.isEmpty()) return;
        EntityManager em = emf.createEntityManager();
        try {
            Double totalVentas = em.createQuery(
                    "SELECT SUM(i.producto.precio * i.cantidad) FROM ItemCarrito i WHERE i.compra IS NOT NULL",
                    Double.class).getSingleResult();
            if (totalVentas == null) totalVentas = 0.0;

            List<Object[]> filas = em.createQuery(
                    "SELECT i.producto.nombre, SUM(i.cantidad) " +
                            "FROM ItemCarrito i WHERE i.compra IS NOT NULL " +
                            "GROUP BY i.producto.nombre ORDER BY SUM(i.cantidad) DESC",
                    Object[].class).getResultList();

            StringBuilder sb = new StringBuilder();
            sb.append("{\"tipo\":\"dashboardUpdate\",");
            sb.append("\"totalVentas\":").append(String.format("%.2f", totalVentas)).append(",");
            sb.append("\"productos\":[");
            for (int i = 0; i < filas.size(); i++) {
                Object[] fila = filas.get(i);
                String nombre  = ((String) fila[0]).replace("\"", "\\\"");
                long   cantidad = (Long) fila[1];
                sb.append("{\"nombre\":\"").append(nombre)
                        .append("\",\"cantidad\":").append(cantidad).append("}");
                if (i < filas.size() - 1) sb.append(",");
            }
            sb.append("]}");

            String msg = sb.toString();
            dashboardWs.removeIf(ctx -> {
                try { ctx.send(msg); return false; }
                catch (Exception e) { return true; }
            });
        } finally {
            em.close();
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Arranque
    // ─────────────────────────────────────────────────────────
    static void main(String[] args) throws Exception {

        Server.createTcpServer("-tcp", "-tcpAllowOthers", "-ifNotExists", "-tcpPort", "9092").start();
        Server.createWebServer("-web", "-webAllowOthers", "-webPort", "8082").start();

        EntityManagerFactory emf = Persistence.createEntityManagerFactory("carritoPU");
        crearAdminSiNoExiste(emf);
        crearDatosBaseSiNoExisten(emf);

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);

        BasicTextEncryptor encryptor = new BasicTextEncryptor();
        encryptor.setPassword("CLAVE_SECRETA");

        Javalin.create(config -> {

            config.staticFiles.add("/publico", Location.CLASSPATH);
            config.fileRenderer(new JavalinThymeleaf(engine));
            config.jetty.port = 7000;

            config.routes.before(ctx -> autoLogin(ctx, emf, encryptor));

            // ══════════════════════════════════════════════════════════
            //  WebSockets
            // ══════════════════════════════════════════════════════════

            config.routes.ws("/ws/online", ws -> {
                ws.onConnect(ctx -> { usuariosWs.add(ctx);    broadcastOnline(); });
                ws.onClose  (ctx -> { usuariosWs.remove(ctx); broadcastOnline(); });
                ws.onError  (ctx ->   usuariosWs.remove(ctx));
            });

            config.routes.ws("/ws/producto/{id}", ws -> {
                ws.onConnect(ctx -> {
                    Long pid = Long.parseLong(ctx.pathParam("id"));
                    productosWs.computeIfAbsent(pid, k -> ConcurrentHashMap.newKeySet()).add(ctx);
                });
                ws.onClose(ctx -> {
                    Long pid = Long.parseLong(ctx.pathParam("id"));
                    Set<WsContext> s = productosWs.get(pid);
                    if (s != null) { s.remove(ctx); if (s.isEmpty()) productosWs.remove(pid); }
                });
                ws.onError(ctx -> {
                    Long pid = Long.parseLong(ctx.pathParam("id"));
                    Set<WsContext> s = productosWs.get(pid);
                    if (s != null) s.remove(ctx);
                });
            });

            config.routes.ws("/ws/dashboard", ws -> {
                ws.onConnect(ctx -> { dashboardWs.add(ctx); broadcastDashboard(emf); });
                ws.onClose  (ctx ->   dashboardWs.remove(ctx));
                ws.onError  (ctx ->   dashboardWs.remove(ctx));
            });

            // ══════════════════════════════════════════════════════════
            //  Rutas HTTP
            // ══════════════════════════════════════════════════════════

            // ── Admin: Productos ──────────────────────────────────────
            config.routes.post("/admin/productos", ctx -> {
                Usuario usuario = ctx.sessionAttribute("usuario");
                if (usuario == null || !usuario.isAdmin()) { ctx.status(401); return; }
                String nombre      = ctx.formParam("nombre");
                String precioStr   = ctx.formParam("precio");
                String descripcion = ctx.formParam("descripcion");
                if (nombre == null || precioStr == null || descripcion == null) {
                    ctx.redirect("/admin/productos"); return;
                }
                List<UploadedFile> archivos = ctx.uploadedFiles("imagenes");
                if (archivos == null || archivos.isEmpty()) {
                    ctx.redirect("/admin/productos?error=imagenes"); return;
                }
                EntityManager em = emf.createEntityManager();
                try {
                    em.getTransaction().begin();
                    Producto producto = new Producto(nombre, Double.parseDouble(precioStr), descripcion);
                    em.persist(producto);
                    for (UploadedFile archivo : archivos) {
                        if (archivo != null && archivo.size() > 0) {
                            byte[] bytes  = archivo.content().readAllBytes();
                            String base64 = Base64.getEncoder().encodeToString(bytes);
                            ImagenProducto imagen = new ImagenProducto(base64, producto);
                            producto.getImagenes().add(imagen);
                            em.persist(imagen);
                        }
                    }
                    em.getTransaction().commit();
                } catch (Exception e) {
                    if (em.getTransaction().isActive()) em.getTransaction().rollback();
                    e.printStackTrace();
                } finally { em.close(); }
                ctx.redirect("/admin/productos");
            });

            config.routes.post("/admin/productos/{id}", ctx -> {
                String method = ctx.formParam("_method");
                if ("PUT".equals(method)) {
                    Usuario usuario = ctx.sessionAttribute("usuario");
                    if (usuario == null || !usuario.isAdmin()) { ctx.status(401); return; }
                    Long id = Long.parseLong(ctx.pathParam("id"));
                    String nombre      = ctx.formParam("nombre");
                    String precioStr   = ctx.formParam("precio");
                    String descripcion = ctx.formParam("descripcion");
                    if (nombre == null || precioStr == null || descripcion == null) { ctx.status(400); return; }
                    EntityManager em = emf.createEntityManager();
                    try {
                        em.getTransaction().begin();
                        Producto p = em.find(Producto.class, id);
                        if (p != null) {
                            p.setNombre(nombre);
                            p.setPrecio(Double.parseDouble(precioStr));
                            p.setDescripcion(descripcion);
                            List<UploadedFile> archivos = ctx.uploadedFiles("imagenes");
                            boolean hayImagenesNuevas = archivos != null && archivos.stream()
                                    .anyMatch(archivo -> archivo != null && archivo.size() > 0);
                            if (hayImagenesNuevas) {
                                p.getImagenes().clear();
                                for (UploadedFile archivo : archivos) {
                                    if (archivo != null && archivo.size() > 0) {
                                        byte[] bytes  = archivo.content().readAllBytes();
                                        String base64 = Base64.getEncoder().encodeToString(bytes);
                                        ImagenProducto imagen = new ImagenProducto(base64, p);
                                        p.getImagenes().add(imagen);
                                        em.persist(imagen);
                                    }
                                }
                            }
                        }
                        em.getTransaction().commit();
                    } catch (Exception e) {
                        if (em.getTransaction().isActive()) em.getTransaction().rollback();
                        e.printStackTrace();
                    } finally { em.close(); }
                    ctx.redirect("/admin/productos");

                } else if ("DELETE".equals(method)) {
                    Usuario usuario = ctx.sessionAttribute("usuario");
                    if (usuario == null || !usuario.isAdmin()) { ctx.status(401); return; }
                    Long id = Long.parseLong(ctx.pathParam("id"));
                    EntityManager em = emf.createEntityManager();
                    try {
                        em.getTransaction().begin();
                        Producto p = em.find(Producto.class, id);
                        if (p != null) em.remove(p);
                        em.getTransaction().commit();
                    } catch (Exception e) {
                        if (em.getTransaction().isActive()) em.getTransaction().rollback();
                        e.printStackTrace();
                    } finally { em.close(); }
                    ctx.redirect("/admin/productos");
                } else { ctx.status(405); }
            });

            config.routes.get("/admin/productos", ctx -> {
                Usuario usuario = ctx.sessionAttribute("usuario");
                if (usuario == null || !usuario.isAdmin()) { ctx.redirect("/login"); return; }
                EntityManager em = emf.createEntityManager();
                List<Producto> productos = em.createQuery(
                        "SELECT DISTINCT p FROM Producto p LEFT JOIN FETCH p.imagenes ORDER BY p.id",
                        Producto.class).getResultList();
                ctx.render("admin-productos.html", Map.of("productos", productos));
                em.close();
            });

            // ── Admin: Dashboard ──────────────────────────────────────
            config.routes.get("/admin/dashboard", ctx -> {
                Usuario admin = ctx.sessionAttribute("usuario");
                if (admin == null || !admin.isAdmin()) { ctx.redirect("/login"); return; }
                EntityManager em = emf.createEntityManager();
                Double totalVentas = em.createQuery(
                        "SELECT SUM(i.producto.precio * i.cantidad) FROM ItemCarrito i WHERE i.compra IS NOT NULL",
                        Double.class).getSingleResult();
                if (totalVentas == null) totalVentas = 0.0;
                List<Object[]> filas = em.createQuery(
                        "SELECT i.producto.nombre, SUM(i.cantidad) " +
                                "FROM ItemCarrito i WHERE i.compra IS NOT NULL " +
                                "GROUP BY i.producto.nombre ORDER BY SUM(i.cantidad) DESC",
                        Object[].class).getResultList();
                List<String> nombresProductos = new ArrayList<>();
                List<Long>   cantidades       = new ArrayList<>();
                for (Object[] fila : filas) {
                    nombresProductos.add((String) fila[0]);
                    cantidades.add((Long) fila[1]);
                }
                Map<String, Object> model = new HashMap<>();
                model.put("totalVentas",      totalVentas);
                model.put("nombresProductos", nombresProductos);
                model.put("cantidades",       cantidades);
                ctx.render("dashboard.html", model);
                em.close();
            });

            // ── Admin: Compras ────────────────────────────────────────
            config.routes.get("/admin/compras", ctx -> {
                Usuario admin = ctx.sessionAttribute("usuario");
                if (admin == null || !admin.isAdmin()) { ctx.redirect("/login"); return; }
                EntityManager em = emf.createEntityManager();
                List<Compra> compras = em.createQuery(
                        "SELECT DISTINCT c FROM Compra c LEFT JOIN FETCH c.items i LEFT JOIN FETCH i.producto ORDER BY c.fecha DESC",
                        Compra.class).getResultList();
                ctx.render("compras.html", Map.of("compras", compras));
                em.close();
            });

            // ── Admin: Usuarios ───────────────────────────────────────
            // FIX: se pasa sesionUsuarioId al modelo para que Thymeleaf
            //      pueda identificar la fila del admin logueado
            config.routes.get("/admin/usuarios", ctx -> {
                Usuario admin = ctx.sessionAttribute("usuario");
                if (admin == null || !admin.isAdmin()) { ctx.redirect("/login"); return; }
                EntityManager em = emf.createEntityManager();
                List<Usuario> usuarios = em.createQuery("FROM Usuario ORDER BY id", Usuario.class).getResultList();
                em.close();
                // ID del admin en sesión para el template
                long sesionUsuarioId = admin.getId();
                ctx.render("admin-usuarios.html", Map.of(
                        "usuarios",        usuarios,
                        "sesionUsuarioId", sesionUsuarioId
                ));
            });

            // FIX: DELETE bloquea al usuario logueado (no solo al hardcoded "admin")
            config.routes.delete("/admin/usuarios/{id}", ctx -> {
                Usuario admin = ctx.sessionAttribute("usuario");
                if (admin == null || !admin.isAdmin()) { ctx.status(401); return; }
                Long id = Long.parseLong(ctx.pathParam("id"));
                // Bloquear auto-eliminación: compara por ID, no por nombre
                if (id == admin.getId()) { ctx.status(403); return; }
                EntityManager em = emf.createEntityManager();
                em.getTransaction().begin();
                Usuario usuario = em.find(Usuario.class, id);
                if (usuario != null) em.remove(usuario);
                em.getTransaction().commit();
                em.close();
                ctx.status(204);
            });

            // FIX: PUT ahora ALTERNA el rol (toggle) en lugar de solo asignar true
            config.routes.put("/admin/usuarios/{id}/admin", ctx -> {
                Usuario admin = ctx.sessionAttribute("usuario");
                if (admin == null || !admin.isAdmin()) { ctx.status(401); return; }
                Long id = Long.parseLong(ctx.pathParam("id"));
                EntityManager em = emf.createEntityManager();
                em.getTransaction().begin();
                Usuario usuario = em.find(Usuario.class, id);
                if (usuario != null) {
                    // Toggle: si era admin lo quita, si no era admin lo pone
                    usuario.setAdmin(!usuario.isAdmin());
                }
                em.getTransaction().commit();
                em.close();
                ctx.status(204);
            });

            // ── Admin: Comentarios ────────────────────────────────────
            config.routes.post("/admin/comentarios/{id}/eliminar", ctx -> {
                Usuario usuario = ctx.sessionAttribute("usuario");
                if (usuario == null || !usuario.isAdmin()) { ctx.status(401); return; }
                Long comentarioId = Long.parseLong(ctx.pathParam("id"));
                EntityManager em = emf.createEntityManager();
                em.getTransaction().begin();
                Comentario comentario = em.find(Comentario.class, comentarioId);
                Long productoId = null;
                if (comentario != null) {
                    productoId = comentario.getProducto().getId();
                    em.remove(comentario);
                }
                em.getTransaction().commit(); em.close();
                if (productoId != null) {
                    broadcastEliminarComentario(productoId, comentarioId);
                    ctx.redirect("/producto/" + productoId);
                } else { ctx.redirect("/"); }
            });

            // ── Página principal ──────────────────────────────────────
            config.routes.get("/", ctx -> {
                int pagina = ctx.queryParam("page") != null
                        ? Integer.parseInt(ctx.queryParam("page")) : 1;
                EntityManager em = emf.createEntityManager();
                Long totalProductos = em.createQuery("SELECT COUNT(p) FROM Producto p", Long.class).getSingleResult();
                int totalPaginas = (int) Math.ceil(totalProductos / 10.0);
                if (totalPaginas < 1) totalPaginas = 1;
                List<Long> ids = em.createQuery("SELECT p.id FROM Producto p ORDER BY p.id", Long.class)
                        .setFirstResult((pagina - 1) * 10).setMaxResults(10).getResultList();
                List<Producto> productos = new ArrayList<>();
                if (!ids.isEmpty()) {
                    productos = em.createQuery(
                            "SELECT DISTINCT p FROM Producto p LEFT JOIN FETCH p.imagenes WHERE p.id IN :ids ORDER BY p.id",
                            Producto.class).setParameter("ids", ids).getResultList();
                }
                Map<String, Object> model = new HashMap<>();
                model.put("productos",       productos);
                model.put("cantidadCarrito", getCantidadCarrito(ctx));
                model.put("pagina",          pagina);
                model.put("totalPaginas",    totalPaginas);
                model.put("usuario",         ctx.sessionAttribute("usuario"));
                ctx.render("index.html", model);
                em.close();
            });

            // ── Detalle de producto ───────────────────────────────────
            config.routes.get("/producto/{id}", ctx -> {
                Long id = Long.parseLong(ctx.pathParam("id"));
                EntityManager em = emf.createEntityManager();
                Producto producto = em.createQuery(
                        "SELECT DISTINCT p FROM Producto p " +
                                "LEFT JOIN FETCH p.comentarios LEFT JOIN FETCH p.imagenes WHERE p.id = :id",
                        Producto.class).setParameter("id", id).getSingleResult();
                if (producto == null) { ctx.status(404); em.close(); return; }
                ctx.render("producto-detalle.html", Map.of(
                        "producto",        producto,
                        "cantidadCarrito", getCantidadCarrito(ctx)));
                em.close();
            });

            config.routes.post("/producto/comentar", ctx -> {
                Usuario usuario = ctx.sessionAttribute("usuario");
                if (usuario == null) { ctx.redirect("/login"); return; }
                Long productoId = Long.parseLong(ctx.formParam("productoId"));
                String texto = ctx.formParam("texto");
                EntityManager em = emf.createEntityManager();
                em.getTransaction().begin();
                Producto producto = em.find(Producto.class, productoId);
                Comentario nuevoComentario = null;
                if (producto != null) {
                    Comentario c = new Comentario(usuario.getUsername(), texto, producto);
                    producto.getComentarios().add(c);
                    em.persist(c);
                    nuevoComentario = c;
                }
                em.getTransaction().commit(); em.close();
                if (nuevoComentario != null && nuevoComentario.getId() != null) {
                    broadcastAgregarComentario(productoId, nuevoComentario.getId(),
                            nuevoComentario.getAutor(), nuevoComentario.getTexto());
                }
                ctx.redirect("/producto/" + productoId);
            });

            // ── Auth ──────────────────────────────────────────────────
            config.routes.get("/login", ctx -> {
                boolean loginError = "1".equals(ctx.queryParam("error"));
                boolean registroOk = "1".equals(ctx.queryParam("registro"));
                ctx.render("login.html", Map.of(
                        "loginError", loginError,
                        "registroOk", registroOk
                ));
            });
            config.routes.get("/registro", ctx -> {
                boolean registroError = "1".equals(ctx.queryParam("error"));
                ctx.render("registro.html", Map.of("registroError", registroError));
            });

            config.routes.post("/login", ctx -> {
                String username = ctx.formParam("username");
                String password = ctx.formParam("password");
                EntityManager em = emf.createEntityManager();
                List<Usuario> usuarios = em.createQuery(
                                "FROM Usuario WHERE username = :u AND password = :p", Usuario.class)
                        .setParameter("u", username).setParameter("p", password).getResultList();
                if (!usuarios.isEmpty()) {
                    Usuario u = usuarios.getFirst();
                    ctx.sessionAttribute("usuario", u);
                    ctx.sessionAttribute("esAdmin", u.isAdmin());
                    if (!u.isAdmin())
                    {
                        // Restaura el carrito guardado en BD en cada login exitoso.
                        List<ItemCarrito> carrito = cargarCarritoDB(emf, u.getUsername());
                        ctx.sessionAttribute("carrito", carrito);
                    }
                    if (ctx.formParam("remember") != null)
                        ctx.cookie("rememberMe", encryptor.encrypt(u.getUsername()), 60 * 60 * 24 * 7);
                    logAuthEvent(username);
                    ctx.redirect(u.isAdmin() ? "/admin/dashboard" : "/");
                } else { ctx.redirect("/login?error=1"); }
                em.close();
            });

            config.routes.post("/registro", ctx -> {
                EntityManager em = emf.createEntityManager();
                String username = ctx.formParam("username");
                String password = ctx.formParam("password");
                if (username == null || password == null || username.isBlank() || password.isBlank()) {
                    em.close();
                    ctx.redirect("/registro?error=1");
                    return;
                }
                List<Usuario> existe = em.createQuery("FROM Usuario WHERE username = :u", Usuario.class)
                        .setParameter("u", username).getResultList();
                if (!existe.isEmpty()) { ctx.redirect("/registro?error=1"); em.close(); return; }
                em.getTransaction().begin();
                Usuario nuevo = new Usuario();
                nuevo.setUsername(username); nuevo.setPassword(password); nuevo.setAdmin(false);
                em.persist(nuevo); em.getTransaction().commit(); em.close();
                ctx.redirect("/login?registro=1");
            });

            config.routes.get("/logout", ctx -> {
                ctx.req().getSession().invalidate();
                ctx.removeCookie("rememberMe");
                ctx.redirect("/");
            });

            // ── Carrito ───────────────────────────────────────────────
            config.routes.post("/carrito/items", ctx -> {
                Usuario usuario = ctx.sessionAttribute("usuario");
                if (usuario == null) { ctx.redirect("/login"); return; }
                Long id       = Long.parseLong(ctx.formParam("id"));
                int  cantidad = Integer.parseInt(ctx.formParam("cantidad"));
                if (cantidad <= 0) { ctx.redirect("/"); return; }
                List<ItemCarrito> carrito = ctx.sessionAttribute("carrito");
                if (carrito == null) carrito = new ArrayList<>();
                EntityManager em = emf.createEntityManager();
                Producto producto = em.find(Producto.class, id);
                if (producto == null) { em.close(); ctx.redirect("/"); return; }
                boolean existe = false;
                for (ItemCarrito item : carrito) {
                    if (item != null && item.getProducto() != null && item.getProducto().getId().equals(id)) {
                        item.setCantidad(item.getCantidad() + cantidad); existe = true; break;
                    }
                }
                if (!existe) carrito.add(new ItemCarrito(producto, cantidad));
                em.close();
                ctx.sessionAttribute("carrito", carrito);
                guardarCarritoDB(emf, usuario.getUsername(), carrito);
                ctx.redirect(ctx.header("Referer") != null ? ctx.header("Referer") : "/");
            });

            config.routes.get("/carrito", ctx -> {
                List<ItemCarrito> carrito = ctx.sessionAttribute("carrito");
                if (carrito == null) { carrito = new ArrayList<>(); ctx.sessionAttribute("carrito", carrito); }
                carrito.removeIf(item -> item == null || item.getProducto() == null);
                double total = carrito.stream().mapToDouble(ItemCarrito::getSubtotal).sum();
                ctx.render("carrito.html", Map.of("items", carrito, "total", total));
            });

            config.routes.post("/carrito/actualizar", ctx -> {
                List<String> ids        = ctx.formParams("id");
                List<String> cantidades = ctx.formParams("cantidad");
                String eliminarId       = ctx.formParam("eliminarId");
                List<ItemCarrito> carrito = ctx.sessionAttribute("carrito");
                if (carrito != null) {
                    Iterator<ItemCarrito> it = carrito.iterator();
                    while (it.hasNext()) {
                        ItemCarrito item = it.next();
                        Long idProducto  = item.getProducto().getId();
                        if (idProducto.toString().equals(eliminarId)) { it.remove(); continue; }
                        for (int i = 0; i < ids.size(); i++) {
                            Long id2          = Long.parseLong(ids.get(i));
                            int nuevaCantidad = Integer.parseInt(cantidades.get(i));
                            if (idProducto.equals(id2)) {
                                if (nuevaCantidad > 0) item.setCantidad(nuevaCantidad);
                                else it.remove();
                            }
                        }
                    }
                    ctx.sessionAttribute("carrito", carrito);
                    Usuario usuario = ctx.sessionAttribute("usuario");
                    if (usuario != null) guardarCarritoDB(emf, usuario.getUsername(), carrito);
                }
                ctx.redirect("/carrito");
            });

            config.routes.delete("/carrito/items/{id}", ctx -> {
                Long id = Long.parseLong(ctx.pathParam("id"));
                List<ItemCarrito> carrito = ctx.sessionAttribute("carrito");
                if (carrito != null) carrito.removeIf(i -> i.getProducto().getId().equals(id));
                ctx.redirect("/carrito");
            });

            // ── Checkout ──────────────────────────────────────────────
            config.routes.post("/carrito/checkout", ctx -> {
                Usuario usuario = ctx.sessionAttribute("usuario");
                if (usuario == null) { ctx.redirect("/login"); return; }
                List<ItemCarrito> carrito = ctx.sessionAttribute("carrito");
                if (carrito != null && !carrito.isEmpty()) {
                    EntityManager em = emf.createEntityManager();
                    try {
                        em.getTransaction().begin();
                        Compra compra = new Compra(usuario.getUsername(), LocalDateTime.now());
                        em.persist(compra);
                        for (ItemCarrito item : carrito) {
                            if (item != null && item.getProducto() != null) {
                                ItemCarrito ip = new ItemCarrito(item.getProducto(), item.getCantidad());
                                ip.setCompra(compra);
                                em.persist(ip);
                                compra.getItems().add(ip);
                            }
                        }
                        em.getTransaction().commit();
                    } finally { em.close(); }
                    ctx.sessionAttribute("carrito", new ArrayList<>());
                    guardarCarritoDB(emf, usuario.getUsername(), new ArrayList<>());
                    broadcastDashboard(emf);
                }
                ctx.redirect("/");
            });

        }).start();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void crearAdminSiNoExiste(EntityManagerFactory emf) {
        EntityManager em = emf.createEntityManager();
        List<Usuario> admins = em.createQuery(
                "FROM Usuario WHERE username = 'admin'", Usuario.class).getResultList();
        if (admins.isEmpty()) {
            em.getTransaction().begin();
            Usuario admin = new Usuario();
            admin.setUsername("admin"); admin.setPassword("admin"); admin.setAdmin(true);
            em.persist(admin); em.getTransaction().commit();
        }
        em.close();
    }

    private static void crearDatosBaseSiNoExisten(EntityManagerFactory emf) {
        try (EntityManager em = emf.createEntityManager()) {
            Long total = em.createQuery("SELECT COUNT(p) FROM Producto p", Long.class).getSingleResult();
            if (total == 0) {
                em.getTransaction().begin();
                Producto producto = new Producto("Producto de muestra", 199.99,
                        "Producto de ejemplo generado automaticamente");
                em.persist(producto);
                String base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=";
                ImagenProducto imagen = new ImagenProducto(base64, producto);
                producto.getImagenes().add(imagen);
                em.persist(imagen);
                em.getTransaction().commit();
            }
        }
    }

    private static void logAuthEvent(String username) {
        String jdbcUrl = System.getenv("JDBC_DATABASE_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            System.out.println("JDBC_DATABASE_URL no configurada; se omite registro externo.");
            return;
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement  stmt  = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS login_audit " +
                            "(username STRING NOT NULL, login_at TIMESTAMPTZ NOT NULL)");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO login_audit (username, login_at) VALUES (?, ?)")) {
                ps.setString(1, username);
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.out.println("No se pudo registrar login externo: " + e.getMessage());
        }
    }

    private static void autoLogin(Context ctx,
                                  EntityManagerFactory emf,
                                  BasicTextEncryptor encryptor) {
        if (ctx.sessionAttribute("usuario") != null) return;
        String cookie = ctx.cookie("rememberMe");
        if (cookie == null) return;
        try {
            String username = encryptor.decrypt(cookie);
            EntityManager em = emf.createEntityManager();
            List<Usuario> usuarios = em.createQuery(
                            "FROM Usuario WHERE username = :u", Usuario.class)
                    .setParameter("u", username).getResultList();
            if (!usuarios.isEmpty()) {
                Usuario u = usuarios.getFirst();
                ctx.sessionAttribute("usuario", u);
                ctx.sessionAttribute("esAdmin", u.isAdmin());
                List<ItemCarrito> carrito = cargarCarritoDB(emf, username);
                ctx.sessionAttribute("carrito", carrito);
            }
            em.close();
        } catch (Exception ignored) {}
    }

    private static int getCantidadCarrito(Context ctx) {
        List<ItemCarrito> carrito = ctx.sessionAttribute("carrito");
        if (carrito == null) return 0;
        return carrito.stream().mapToInt(ItemCarrito::getCantidad).sum();
    }

    static void guardarCarritoDB(EntityManagerFactory emf,
                                 String username,
                                 List<ItemCarrito> carrito) {
        if (username == null) return;
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createQuery("DELETE FROM CarritoGuardado c WHERE c.username = :u")
                    .setParameter("u", username).executeUpdate();
            if (carrito != null) {
                for (ItemCarrito item : carrito) {
                    if (item != null && item.getProducto() != null && item.getCantidad() > 0) {
                        CarritoGuardado cg = new CarritoGuardado(
                                username, item.getProducto().getId(), item.getCantidad());
                        em.persist(cg);
                    }
                }
            }
            em.getTransaction().commit();
        } catch (Exception e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            e.printStackTrace();
        } finally { em.close(); }
    }

    static List<ItemCarrito> cargarCarritoDB(EntityManagerFactory emf, String username) {
        List<ItemCarrito> carrito = new ArrayList<>();
        if (username == null) return carrito;
        EntityManager em = emf.createEntityManager();
        try {
            List<CarritoGuardado> guardados = em.createQuery(
                            "FROM CarritoGuardado c WHERE c.username = :u", CarritoGuardado.class)
                    .setParameter("u", username).getResultList();
            for (CarritoGuardado cg : guardados) {
                Producto p = em.find(Producto.class, cg.getProductoId());
                if (p != null) carrito.add(new ItemCarrito(p, cg.getCantidad()));
            }
        } finally { em.close(); }
        return carrito;
    }
}

