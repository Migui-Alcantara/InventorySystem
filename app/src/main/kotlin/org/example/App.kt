package org.example

import java.sql.DriverManager
import java.sql.ResultSet

data class InventoryItem(
    val id: Int,
    val name: String,
    val category: String,
    var quantity: Int,
    var spoilage: Int,
    var restockThreshold: Int,
    var unitPrice: Double
) {
    fun needsRestock(): Boolean = quantity <= restockThreshold
    fun restockAmount(): Int = if (needsRestock()) (restockThreshold * 2) - quantity else 0
    fun totalValue(): Double = quantity * unitPrice
}

class InventoryRepository {
    private val url = "jdbc:sqlite:./database/inventory.db"

    init {
        val conn = connect()
        conn?.createStatement()?.execute("""
            CREATE TABLE IF NOT EXISTS items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                category TEXT,
                quantity INTEGER,
                spoilage INTEGER,
                restock_threshold INTEGER,
                unit_price REAL
            )
        """)
        conn?.close()
    }

    private fun connect() = DriverManager.getConnection(url)

    fun add(item: InventoryItem): InventoryItem {
        val conn = connect()
        val stmt = conn?.prepareStatement(
            "INSERT INTO items (name, category, quantity, spoilage, restock_threshold, unit_price) VALUES (?, ?, ?, ?, ?, ?)"
        )
        stmt?.setString(1, item.name)
        stmt?.setString(2, item.category)
        stmt?.setInt(3, item.quantity)
        stmt?.setInt(4, item.spoilage)
        stmt?.setInt(5, item.restockThreshold)
        stmt?.setDouble(6, item.unitPrice)
        stmt?.executeUpdate()

        val rs = conn?.createStatement()?.executeQuery("SELECT last_insert_rowid()")
        val newId = rs?.getInt(1) ?: 0
        conn?.close()
        return item.copy(id = newId)
    }

    fun update(id: Int, updated: InventoryItem): Boolean {
        val conn = connect()
        val stmt = conn?.prepareStatement(
            "UPDATE items SET name=?, category=?, quantity=?, spoilage=?, restock_threshold=?, unit_price=? WHERE id=?"
        )
        stmt?.setString(1, updated.name)
        stmt?.setString(2, updated.category)
        stmt?.setInt(3, updated.quantity)
        stmt?.setInt(4, updated.spoilage)
        stmt?.setInt(5, updated.restockThreshold)
        stmt?.setDouble(6, updated.unitPrice)
        stmt?.setInt(7, id)
        val rows = stmt?.executeUpdate() ?: 0
        conn?.close()
        return rows > 0
    }

    fun delete(id: Int): Boolean {
        val conn = connect()
        val stmt = conn?.prepareStatement("DELETE FROM items WHERE id=?")
        stmt?.setInt(1, id)
        val rows = stmt?.executeUpdate() ?: 0
        conn?.close()
        return rows > 0
    }

    fun findById(id: Int): InventoryItem? {
        val conn = connect()
        val stmt = conn?.prepareStatement("SELECT * FROM items WHERE id=?")
        stmt?.setInt(1, id)
        val rs = stmt?.executeQuery()
        val item = if (rs?.next() == true) rs.toInventoryItem() else null
        conn?.close()
        return item
    }

    fun getAll(): List<InventoryItem> {
        val conn = connect()
        val rs = conn?.createStatement()?.executeQuery("SELECT * FROM items")
        val items = mutableListOf<InventoryItem>()
        while (rs?.next() == true) items.add(rs.toInventoryItem())
        conn?.close()
        return items
    }

    fun clear() {
        val conn = connect()
        conn?.createStatement()?.execute("DELETE FROM items")
        conn?.close()
        println("Database cleared. All records removed.")
    }

    private fun ResultSet.toInventoryItem() = InventoryItem(
        id = getInt("id"),
        name = getString("name"),
        category = getString("category"),
        quantity = getInt("quantity"),
        spoilage = getInt("spoilage"),
        restockThreshold = getInt("restock_threshold"),
        unitPrice = getDouble("unit_price")
    )
}

class InventoryService(private val repo: InventoryRepository) {

    fun addItem(name: String, category: String, quantity: Int,
                spoilage: Int, restockThreshold: Int, unitPrice: Double) {
        val item = InventoryItem(
            id = 0, name = name, category = category,
            quantity = quantity, spoilage = spoilage,
            restockThreshold = restockThreshold, unitPrice = unitPrice
        )
        val saved = repo.add(item)
        println("'${saved.name}' added with ID #${saved.id}.")
    }

    fun editItem(id: Int, name: String, category: String, quantity: Int,
                 spoilage: Int, restockThreshold: Int, unitPrice: Double) {
        val existing = repo.findById(id) ?: run { println("Item #$id not found."); return }
        val updated = existing.copy(
            name = name, category = category, quantity = quantity,
            spoilage = spoilage, restockThreshold = restockThreshold, unitPrice = unitPrice
        )
        if (repo.update(id, updated)) println("Item #$id updated.")
        else println("Update failed.")
    }

    fun deleteItem(id: Int) {
        if (repo.delete(id)) println("Item #$id deleted.")
        else println("Item #$id not found.")
    }

    fun listItems() {
        val items = repo.getAll()
        if (items.isEmpty()) { println("\nNo items in inventory."); return }

        println("\n%-5s %-20s %-15s %8s %9s %10s %10s %9s"
            .format("ID", "Name", "Category", "Qty", "Spoilage", "Restock@", "Price", "Restock?"))
        println("-".repeat(95))
        items.forEach { i ->
            println("%-5d %-20s %-15s %8d %9d %10d %10.2f %9s"
                .format(i.id, i.name, i.category, i.quantity,
                        i.spoilage, i.restockThreshold, i.unitPrice,
                        if (i.needsRestock()) "YES" else "No"))
        }
    }

    fun computeSummary() {
        val items = repo.getAll()
        if (items.isEmpty()) { println("No items to summarize."); return }

        val totalQty    = items.sumOf { it.quantity }
        val totalSpoil  = items.sumOf { it.spoilage }
        val totalValue  = items.sumOf { it.totalValue() }
        val restockList = items.filter { it.needsRestock() }

        println("\n=== INVENTORY SUMMARY ===")
        println("Total SKUs         : ${items.size}")
        println("Total Quantity     : $totalQty")
        println("Total Spoilage     : $totalSpoil")
        println("Total Value        : ${"%.2f".format(totalValue)}")

        if (restockList.isEmpty()) {
            println("All items are sufficiently stocked.")
        } else {
            println("\nItems Needing Restock:")
            restockList.forEach { i ->
                println("  - ${i.name} needs +${i.restockAmount()} units")
            }
        }
    }
}

class InventoryApp {
    private val repo    = InventoryRepository()
    private val service = InventoryService(repo)

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\nShutting down...")
        })

        var running = true
        while (running) {
            printMenu()
            when (readLine()?.trim()) {
                "1" -> handleAdd()
                "2" -> handleEdit()
                "3" -> handleDelete()
                "4" -> service.listItems()
                "5" -> service.computeSummary()
                "0" -> { running = false; println("Goodbye!") }
                else -> println("Invalid option. Try again.")
            }
        }
    }

    private fun printMenu() {
        println("\n=== KOTLIN INVENTORY SYSTEM ===")
        println("1. Add Item")
        println("2. Edit Item")
        println("3. Delete Item")
        println("4. View All Items")
        println("5. Inventory Summary")
        println("0. Exit")
        print("Enter choice: ")
    }

    private fun handleAdd() {
        println("\n-- Add New Item --")
        val fields = promptItemFields()
        service.addItem(fields[0] as String, fields[1] as String, fields[2] as Int,
                        fields[3] as Int, fields[4] as Int, fields[5] as Double)
    }

    private fun handleEdit() {
        println("\n-- Edit Item --")
        service.listItems()
        print("Enter Item ID to edit: ")
        val id = readLine()?.trim()?.toIntOrNull() ?: run { println("Invalid ID."); return }
        println("Enter new values:")
        val fields = promptItemFields()
        service.editItem(id, fields[0] as String, fields[1] as String, fields[2] as Int,
                         fields[3] as Int, fields[4] as Int, fields[5] as Double)
    }

    private fun handleDelete() {
        println("\n-- Delete Item --")
        service.listItems()
        print("Enter Item ID to delete: ")
        val id = readLine()?.trim()?.toIntOrNull() ?: run { println("Invalid ID."); return }
        print("Confirm delete #$id? (y/n): ")
        if (readLine()?.trim()?.lowercase() == "y") service.deleteItem(id)
        else println("Cancelled.")
    }

    private fun promptItemFields(): List<Any> {
        print("  Name            : "); val name    = readLine()?.trim() ?: "Unknown"
        print("  Category        : "); val cat     = readLine()?.trim() ?: "General"
        print("  Quantity        : "); val qty     = readLine()?.trim()?.toIntOrNull() ?: 0
        print("  Spoilage        : "); val spoil   = readLine()?.trim()?.toIntOrNull() ?: 0
        print("  Restock at (qty): "); val restock = readLine()?.trim()?.toIntOrNull() ?: 10
        print("  Unit Price      : "); val price   = readLine()?.trim()?.toDoubleOrNull() ?: 0.0
        return listOf(name, cat, qty, spoil, restock, price)
    }
}

fun main() {
    InventoryApp().run()
}