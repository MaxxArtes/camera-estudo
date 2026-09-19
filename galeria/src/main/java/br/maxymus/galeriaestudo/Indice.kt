package br.maxymus.galeriaestudo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Índice local de rostos e pessoas (SQLite puro; sem Room para não somar processador de anotações ao build).
 * Só números, caixas e vetores; nenhuma imagem além das capas de 160 px em files/. allowBackup=false no manifesto.
 * Correções do usuário (nomes, junções, "não é esta pessoa", ocultar) ficam em tabelas próprias e sobrevivem à reanálise.
 */
class Indice private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "indice.db", null, 2) {
    companion object {
        @Volatile private var inst: Indice? = null
        fun get(ctx: Context): Indice = inst ?: synchronized(this) { inst ?: Indice(ctx).also { inst = it } }
        fun capa(ctx: Context, pessoa: Long): File = File(ctx.filesDir, "capa_$pessoa.jpg")
    private const val SQL_RESUMO = """SELECT p.id, p.nome, p.oculta,
            (SELECT COUNT(DISTINCT r.foto) FROM rostos r WHERE r.pessoa=p.id AND NOT EXISTS (SELECT 1 FROM exclusoes e WHERE e.foto=r.foto AND e.pessoa=p.id)) AS n,
            (SELECT MAX(f.quando) FROM rostos r JOIN fotos f ON f.id=r.foto WHERE r.pessoa=p.id) AS ultima
            FROM pessoas p WHERE p.absorvida_por IS NULL"""
    }

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE fotos(id INTEGER PRIMARY KEY, quando INTEGER NOT NULL, analisada INTEGER NOT NULL DEFAULT 0, n_rostos INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE pessoas(id INTEGER PRIMARY KEY AUTOINCREMENT, nome TEXT, oculta INTEGER NOT NULL DEFAULT 0, capa INTEGER, capa_nota REAL NOT NULL DEFAULT 0, absorvida_por INTEGER, criado INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE rostos(id INTEGER PRIMARY KEY AUTOINCREMENT, foto INTEGER NOT NULL, pessoa INTEGER, nx REAL, ny REAL, nw REAL, nh REAL, vetor BLOB NOT NULL, nota REAL NOT NULL DEFAULT 0, exemplar INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX i_rostos_pessoa ON rostos(pessoa)")
        db.execSQL("CREATE INDEX i_rostos_foto ON rostos(foto)")
        db.execSQL("CREATE INDEX i_fotos_analisada ON fotos(analisada)")
        db.execSQL("CREATE INDEX i_fotos_quando ON fotos(quando)")
        db.execSQL("CREATE TABLE exclusoes(foto INTEGER NOT NULL, pessoa INTEGER NOT NULL, PRIMARY KEY(foto, pessoa))")
        db.execSQL("CREATE TABLE juncoes(id INTEGER PRIMARY KEY AUTOINCREMENT, de_pessoa INTEGER NOT NULL, para_pessoa INTEGER NOT NULL, quando INTEGER NOT NULL, rostos TEXT NOT NULL, nome_de TEXT, nome_para TEXT, desfeita INTEGER NOT NULL DEFAULT 0)")
        criaAlbuns(db)
    }

    /** Álbuns manuais (o dono monta): tabela de álbuns + itens (foto do MediaStore). Criadas na v2. */
    private fun criaAlbuns(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS albuns(id INTEGER PRIMARY KEY AUTOINCREMENT, nome TEXT NOT NULL, criado INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS album_itens(album INTEGER NOT NULL, foto INTEGER NOT NULL, quando INTEGER NOT NULL, adicionado INTEGER NOT NULL, PRIMARY KEY(album, foto))")
        db.execSQL("CREATE INDEX IF NOT EXISTS i_album_itens_album ON album_itens(album)")
    }
    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) { if (antiga < 2) criaAlbuns(db) }

    fun <T> transacao(bloco: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val r = bloco(db); db.setTransactionSuccessful(); return r } finally { db.endTransaction() }
    }

    // ---------- fotos ----------
    fun fotosAnalisadas(): HashSet<Long> {
        val s = HashSet<Long>()
        readableDatabase.rawQuery("SELECT id FROM fotos WHERE analisada=1", null).use { c -> while (c.moveToNext()) s += c.getLong(0) }
        return s
    }
    fun upsertFoto(db: SQLiteDatabase, id: Long, quando: Long) {
        db.execSQL("INSERT OR IGNORE INTO fotos(id, quando) VALUES(?, ?)", arrayOf<Any?>(id, quando))
        db.execSQL("UPDATE fotos SET quando=? WHERE id=?", arrayOf<Any?>(quando, id))
    }
    fun marcaAnalisada(db: SQLiteDatabase, id: Long, nRostos: Int) { db.execSQL("UPDATE fotos SET analisada=1, n_rostos=? WHERE id=?", arrayOf<Any?>(nRostos, id)) }

    // ---------- pessoas e rostos ----------
    fun criaPessoa(db: SQLiteDatabase): Long = db.insert("pessoas", null, ContentValues().apply { put("criado", System.currentTimeMillis()) })
    fun insereRosto(db: SQLiteDatabase, foto: Long, pessoa: Long?, nx: Float, ny: Float, nw: Float, nh: Float, vetor: FloatArray, nota: Float, exemplar: Boolean): Long =
        db.insert("rostos", null, ContentValues().apply {
            put("foto", foto); if (pessoa != null) put("pessoa", pessoa) else putNull("pessoa"); put("nx", nx); put("ny", ny); put("nw", nw); put("nh", nh)
            put("vetor", Embedding.paraBytes(vetor)); put("nota", nota); put("exemplar", if (exemplar) 1 else 0)
        })
    fun capaNota(db: SQLiteDatabase, pessoa: Long): Float = db.rawQuery("SELECT capa_nota FROM pessoas WHERE id=?", arrayOf(pessoa.toString())).use { if (it.moveToFirst()) it.getFloat(0) else 0f }
    fun defineCapa(db: SQLiteDatabase, pessoa: Long, rosto: Long, nota: Float) { db.execSQL("UPDATE pessoas SET capa=?, capa_nota=? WHERE id=?", arrayOf<Any?>(rosto, nota, pessoa)) }

    class Exemplar(val rosto: Long, val vetor: FloatArray)

    /** Vetores-exemplar por pessoa viva (não absorvida), com o id do rosto, para a atribuição em linha. */
    fun carregaExemplares(): HashMap<Long, MutableList<Exemplar>> {
        val m = HashMap<Long, MutableList<Exemplar>>()
        readableDatabase.rawQuery("SELECT id FROM pessoas WHERE absorvida_por IS NULL", null).use { c -> while (c.moveToNext()) m[c.getLong(0)] = mutableListOf() }
        readableDatabase.rawQuery("SELECT id, pessoa, vetor FROM rostos WHERE exemplar=1 AND pessoa IS NOT NULL", null).use { c ->
            while (c.moveToNext()) { m[c.getLong(1)]?.add(Exemplar(c.getLong(0), Embedding.deBytes(c.getBlob(2)))) }
        }
        return m
    }
    fun desmarcaExemplar(db: SQLiteDatabase, rosto: Long) { db.execSQL("UPDATE rostos SET exemplar=0 WHERE id=?", arrayOf<Any?>(rosto)) }
    fun nomeDaPessoa(id: Long): String? = readableDatabase.rawQuery("SELECT nome FROM pessoas WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
    fun carregaExclusoes(): HashMap<Long, HashSet<Long>> {
        val m = HashMap<Long, HashSet<Long>>()
        readableDatabase.rawQuery("SELECT foto, pessoa FROM exclusoes", null).use { c -> while (c.moveToNext()) m.getOrPut(c.getLong(0)) { HashSet() }.add(c.getLong(1)) }
        return m
    }

    class Resumo(val id: Long, val nome: String?, val fotos: Int, val ultima: Long, val oculta: Boolean)

    /** Pessoas visíveis (ou ocultas), com fotos distintas (descontando "não é esta pessoa"), mais fotos primeiro. */
    fun pessoas(ocultas: Boolean = false): List<Resumo> {
        val lista = ArrayList<Resumo>()
        readableDatabase.rawQuery("$SQL_RESUMO AND p.oculta=? ORDER BY n DESC, ultima DESC", arrayOf(if (ocultas) "1" else "0")).use { c ->
            while (c.moveToNext()) { val n = c.getInt(3); if (n > 0) lista += Resumo(c.getLong(0), c.getString(1), n, if (c.isNull(4)) 0L else c.getLong(4), c.getInt(2) == 1) }
        }
        return lista
    }
    fun resumo(id: Long): Resumo? = readableDatabase.rawQuery("$SQL_RESUMO AND p.id=?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) Resumo(c.getLong(0), c.getString(1), c.getInt(3), if (c.isNull(4)) 0L else c.getLong(4), c.getInt(2) == 1) else null
    }
    /** Ids das fotos da pessoa, mais recentes primeiro, sem as marcadas "não é esta pessoa". */
    fun fotosDaPessoa(id: Long): List<Long> {
        val lista = ArrayList<Long>()
        readableDatabase.rawQuery("""SELECT DISTINCT r.foto, f.quando FROM rostos r JOIN fotos f ON f.id=r.foto WHERE r.pessoa=?
            AND NOT EXISTS (SELECT 1 FROM exclusoes e WHERE e.foto=r.foto AND e.pessoa=r.pessoa) ORDER BY f.quando DESC, r.foto DESC""", arrayOf(id.toString())).use { c -> while (c.moveToNext()) lista += c.getLong(0) }
        return lista
    }
    fun renomear(id: Long, nome: String?) { writableDatabase.execSQL("UPDATE pessoas SET nome=? WHERE id=?", arrayOf<Any?>(nome?.trim()?.ifEmpty { null }, id)) }
    fun ocultar(id: Long, v: Boolean) { writableDatabase.execSQL("UPDATE pessoas SET oculta=? WHERE id=?", arrayOf<Any?>(if (v) 1 else 0, id)) }

    class Juncao(val id: Long, val de: Long, val para: Long, val nomeDe: String?, val nomePara: String?)

    /** Move todos os rostos de `de` para `para`, guarda a operação para desfazer; `nome` é o que fica no resultado. */
    fun juntar(de: Long, para: Long, nome: String?): Long = transacao { db ->
        val ids = ArrayList<Long>()
        db.rawQuery("SELECT id FROM rostos WHERE pessoa=?", arrayOf(de.toString())).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        val nomeDe = db.rawQuery("SELECT nome FROM pessoas WHERE id=?", arrayOf(de.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        val nomePara = db.rawQuery("SELECT nome FROM pessoas WHERE id=?", arrayOf(para.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        db.execSQL("UPDATE rostos SET pessoa=? WHERE pessoa=?", arrayOf<Any?>(para, de))
        db.execSQL("UPDATE exclusoes SET pessoa=? WHERE pessoa=? AND foto NOT IN (SELECT foto FROM exclusoes WHERE pessoa=?)", arrayOf<Any?>(para, de, para))
        db.execSQL("DELETE FROM exclusoes WHERE pessoa=?", arrayOf<Any?>(de))
        db.execSQL("UPDATE pessoas SET absorvida_por=? WHERE id=?", arrayOf<Any?>(para, de))
        db.execSQL("UPDATE pessoas SET nome=? WHERE id=?", arrayOf<Any?>(nome, para))
        db.insert("juncoes", null, ContentValues().apply { put("de_pessoa", de); put("para_pessoa", para); put("quando", System.currentTimeMillis()); put("rostos", ids.joinToString(",")); put("nome_de", nomeDe); put("nome_para", nomePara) })
    }
    fun ultimaJuncao(para: Long): Juncao? = readableDatabase.rawQuery("SELECT id, de_pessoa, para_pessoa, nome_de, nome_para FROM juncoes WHERE para_pessoa=? AND desfeita=0 ORDER BY id DESC LIMIT 1", arrayOf(para.toString())).use { c ->
        if (c.moveToFirst()) Juncao(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)) else null
    }
    fun desfazJuncao(id: Long): Unit = transacao { db ->
        val j = db.rawQuery("SELECT de_pessoa, para_pessoa, rostos, nome_de, nome_para FROM juncoes WHERE id=? AND desfeita=0", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) arrayOf<Any?>(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4)) else null
        } ?: return@transacao
        val de = j[0] as Long; val para = j[1] as Long
        for (r in (j[2] as String).split(",")) if (r.isNotBlank()) db.execSQL("UPDATE rostos SET pessoa=? WHERE id=?", arrayOf<Any?>(de, r.toLong()))
        db.execSQL("UPDATE pessoas SET absorvida_por=NULL, nome=? WHERE id=?", arrayOf<Any?>(j[3], de))
        db.execSQL("UPDATE pessoas SET nome=? WHERE id=?", arrayOf<Any?>(j[4], para))
        db.execSQL("UPDATE juncoes SET desfeita=1 WHERE id=?", arrayOf<Any?>(id))
    }

    /** "Não é esta pessoa": esconde a foto do álbum e tira o rosto dos exemplares; a reanálise respeita. */
    fun naoEEstaPessoa(foto: Long, pessoa: Long): Unit = transacao { db ->
        db.execSQL("INSERT OR IGNORE INTO exclusoes(foto, pessoa) VALUES(?, ?)", arrayOf<Any?>(foto, pessoa))
        db.execSQL("UPDATE rostos SET exemplar=0 WHERE foto=? AND pessoa=?", arrayOf<Any?>(foto, pessoa))
    }
    fun desfazNaoE(foto: Long, pessoa: Long) { writableDatabase.execSQL("DELETE FROM exclusoes WHERE foto=? AND pessoa=?", arrayOf<Any?>(foto, pessoa)) }

    // ---------- álbuns manuais ----------
    class AlbumManual(val id: Long, val nome: String, val fotos: Int, val capa: Long?, val criado: Long)

    fun criarAlbum(nome: String): Long = writableDatabase.insert("albuns", null, ContentValues().apply { put("nome", nome.trim()); put("criado", System.currentTimeMillis()) })
    fun renomearAlbum(id: Long, nome: String) { writableDatabase.execSQL("UPDATE albuns SET nome=? WHERE id=?", arrayOf<Any?>(nome.trim(), id)) }
    fun apagarAlbum(id: Long): Unit = transacao { db -> db.execSQL("DELETE FROM album_itens WHERE album=?", arrayOf<Any?>(id)); db.execSQL("DELETE FROM albuns WHERE id=?", arrayOf<Any?>(id)) }

    /** Adiciona fotos (id do MediaStore + carimbo de data) ao álbum; ignora as que já estão (PRIMARY KEY). Devolve quantas entraram. */
    fun adicionarAoAlbum(album: Long, fotos: List<Pair<Long, Long>>): Int = transacao { db ->
        var n = 0; val agora = System.currentTimeMillis()
        for ((foto, quando) in fotos) {
            val v = ContentValues().apply { put("album", album); put("foto", foto); put("quando", quando); put("adicionado", agora) }
            if (db.insertWithOnConflict("album_itens", null, v, SQLiteDatabase.CONFLICT_IGNORE) >= 0) n++
        }
        n
    }
    fun removerDoAlbum(album: Long, foto: Long) { writableDatabase.execSQL("DELETE FROM album_itens WHERE album=? AND foto=?", arrayOf<Any?>(album, foto)) }

    fun listarAlbuns(): List<AlbumManual> {
        val lista = ArrayList<AlbumManual>()
        readableDatabase.rawQuery("""SELECT a.id, a.nome, a.criado,
            (SELECT COUNT(*) FROM album_itens i WHERE i.album=a.id) AS n,
            (SELECT i.foto FROM album_itens i WHERE i.album=a.id ORDER BY i.quando DESC LIMIT 1) AS capa
            FROM albuns a ORDER BY a.criado DESC""", null).use { c ->
            while (c.moveToNext()) lista += AlbumManual(c.getLong(0), c.getString(1), c.getInt(3), if (c.isNull(4)) null else c.getLong(4), c.getLong(2))
        }
        return lista
    }
    fun albumManual(id: Long): AlbumManual? = readableDatabase.rawQuery("""SELECT a.id, a.nome, a.criado,
        (SELECT COUNT(*) FROM album_itens i WHERE i.album=a.id) AS n,
        (SELECT i.foto FROM album_itens i WHERE i.album=a.id ORDER BY i.quando DESC LIMIT 1) AS capa
        FROM albuns a WHERE a.id=?""", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) AlbumManual(c.getLong(0), c.getString(1), c.getInt(3), if (c.isNull(4)) null else c.getLong(4), c.getLong(2)) else null
    }
    /** Ids das fotos do álbum, mais recentes primeiro. */
    fun fotosDoAlbum(id: Long): List<Long> {
        val l = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT foto FROM album_itens WHERE album=? ORDER BY quando DESC, foto DESC", arrayOf(id.toString())).use { c -> while (c.moveToNext()) l += c.getLong(0) }
        return l
    }
    /** Ids dos álbuns manuais que já contêm a foto (para marcar no seletor). */
    fun albunsDaFoto(foto: Long): Set<Long> {
        val s = HashSet<Long>()
        readableDatabase.rawQuery("SELECT album FROM album_itens WHERE foto=?", arrayOf(foto.toString())).use { c -> while (c.moveToNext()) s += c.getLong(0) }
        return s
    }

    /** Apaga rostos, pessoas e correções; as fotos ficam e voltam a "não analisadas". */
    fun apagarTudo(ctx: Context): Unit = transacao { db ->
        db.execSQL("DELETE FROM rostos"); db.execSQL("DELETE FROM pessoas"); db.execSQL("DELETE FROM exclusoes"); db.execSQL("DELETE FROM juncoes")
        db.execSQL("UPDATE fotos SET analisada=0, n_rostos=0")
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("capa_") }?.forEach { it.delete() }
    }
}
