"use client"

import { useEffect, useState } from "react"
import { motion } from "framer-motion"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"
import apiClient from "@/lib/api-client"
import { useToast } from "@/hooks/use-toast"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Badge } from "@/components/ui/badge"
import { Package, Plus, Pencil, Trash2, AlertCircle } from "lucide-react"
import type { Product, CreateProductRequest } from "@/types/api"
import {
  ALLERGENS,
  hasAllergen,
  toggleAllergen,
  getAllergenNames,
} from "@/types/api"

const productSchema = z.object({
  sku: z.string().min(1, "SKU is required").max(50, "SKU too long"),
  title: z.string().min(1, "Title is required").max(200, "Title too long"),
  ingredientsText: z
    .string()
    .min(1, "Ingredients are required")
    .max(1000, "Ingredients text too long"),
})

type ProductFormData = z.infer<typeof productSchema>

export default function ProductsPage() {
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [editingProduct, setEditingProduct] = useState<Product | null>(null)
  const [deletingProduct, setDeletingProduct] = useState<Product | null>(null)
  const [allergenMask, setAllergenMask] = useState(0)
  const [submitting, setSubmitting] = useState(false)
  const { toast } = useToast()

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setValue,
  } = useForm<ProductFormData>({
    resolver: zodResolver(productSchema),
  })

  useEffect(() => {
    fetchProducts()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const fetchProducts = async () => {
    try {
      setLoading(true)
      const response = await apiClient.get(
        "/products?size=100&sort=createdAt,desc"
      )
      setProducts(response.data.content || [])
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to load products"
      toast({
        variant: "destructive",
        title: "Error loading products",
        description: errorMessage,
      })
    } finally {
      setLoading(false)
    }
  }

  const openCreateDialog = () => {
    setEditingProduct(null)
    reset({ sku: "", title: "", ingredientsText: "" })
    setAllergenMask(0)
    setDialogOpen(true)
  }

  const openEditDialog = (product: Product) => {
    setEditingProduct(product)
    setValue("sku", product.sku)
    setValue("title", product.title)
    setValue("ingredientsText", product.ingredientsText)
    setAllergenMask(product.allergenMask)
    setDialogOpen(true)
  }

  const openDeleteDialog = (product: Product) => {
    setDeletingProduct(product)
    setDeleteDialogOpen(true)
  }

  const toggleAllergenBit = (bit: number) => {
    setAllergenMask(toggleAllergen(allergenMask, bit))
  }

  const onSubmit = async (data: ProductFormData) => {
    try {
      setSubmitting(true)

      const payload: CreateProductRequest = {
        ...data,
        allergenMask,
      }

      if (editingProduct) {
        // Update existing product
        await apiClient.put(`/products/${editingProduct.id}`, payload)
        toast({
          title: "Product updated",
          description: `${data.title} has been updated successfully.`,
        })
      } else {
        // Create new product
        await apiClient.post("/products", payload)
        toast({
          title: "Product created",
          description: `${data.title} has been created successfully.`,
        })
      }

      setDialogOpen(false)
      reset()
      setAllergenMask(0)
      fetchProducts()
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : `Failed to ${editingProduct ? "update" : "create"} product`
      toast({
        variant: "destructive",
        title: editingProduct ? "Error updating product" : "Error creating product",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async () => {
    if (!deletingProduct) return

    try {
      setSubmitting(true)
      await apiClient.delete(`/products/${deletingProduct.id}`)
      toast({
        title: "Product deleted",
        description: `${deletingProduct.title} has been deleted successfully.`,
      })
      setDeleteDialogOpen(false)
      setDeletingProduct(null)
      fetchProducts()
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to delete product"
      toast({
        variant: "destructive",
        title: "Error deleting product",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <motion.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="flex items-center justify-between"
      >
        <div>
          <h1 className="text-4xl font-bold text-slate-900">Products</h1>
          <p className="mt-2 text-slate-600">
            Manage your product catalog with allergen information
          </p>
        </div>
        <Button onClick={openCreateDialog} className="gap-2">
          <Plus className="h-4 w-4" />
          Add Product
        </Button>
      </motion.div>

      {/* Products Table */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
      >
        <Card>
          <CardHeader>
            <CardTitle>All Products</CardTitle>
            <CardDescription>
              {products.length} product{products.length !== 1 ? "s" : ""} in total
            </CardDescription>
          </CardHeader>
          <CardContent>
            {products.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <Package className="mb-4 h-12 w-12 text-slate-300" />
                <h3 className="mb-2 text-lg font-semibold text-slate-900">
                  No products yet
                </h3>
                <p className="mb-4 text-sm text-slate-500">
                  Get started by creating your first product
                </p>
                <Button onClick={openCreateDialog} variant="outline">
                  <Plus className="mr-2 h-4 w-4" />
                  Add Product
                </Button>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>SKU</TableHead>
                      <TableHead>Title</TableHead>
                      <TableHead>Allergens</TableHead>
                      <TableHead className="text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {products.map((product) => {
                      const allergenNames = getAllergenNames(product.allergenMask)
                      return (
                        <motion.tr
                          key={product.id}
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          className="group"
                        >
                          <TableCell className="font-mono text-sm font-medium">
                            {product.sku}
                          </TableCell>
                          <TableCell>
                            <div className="flex items-center gap-2">
                              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-purple-100 text-purple-600">
                                <Package className="h-4 w-4" />
                              </div>
                              <div>
                                <div className="font-medium">{product.title}</div>
                                <div className="line-clamp-1 text-xs text-slate-500">
                                  {product.ingredientsText}
                                </div>
                              </div>
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="flex flex-wrap gap-1">
                              {allergenNames.length === 0 ? (
                                <span className="text-sm text-slate-400">
                                  No allergens
                                </span>
                              ) : (
                                allergenNames.map((name) => {
                                  const allergen = ALLERGENS.find(
                                    (a) => a.name === name
                                  )
                                  return (
                                    <Badge
                                      key={name}
                                      variant="outline"
                                      className="bg-orange-50 text-orange-700 border-orange-200"
                                    >
                                      <span className="mr-1">{allergen?.icon}</span>
                                      {name}
                                    </Badge>
                                  )
                                })
                              )}
                            </div>
                          </TableCell>
                          <TableCell className="text-right">
                            <div className="flex justify-end gap-2">
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => openEditDialog(product)}
                                className="h-8 w-8 p-0"
                              >
                                <Pencil className="h-4 w-4" />
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => openDeleteDialog(product)}
                                className="h-8 w-8 p-0 text-red-600 hover:bg-red-50 hover:text-red-700"
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </div>
                          </TableCell>
                        </motion.tr>
                      )
                    })}
                  </TableBody>
                </Table>
              </div>
            )}
          </CardContent>
        </Card>
      </motion.div>

      {/* Create/Edit Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {editingProduct ? "Edit Product" : "Create New Product"}
            </DialogTitle>
            <DialogDescription>
              {editingProduct
                ? "Update the product details below."
                : "Add a new product to your catalog."}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="sku">SKU</Label>
              <Input
                id="sku"
                placeholder="e.g., PROD-001"
                {...register("sku")}
              />
              {errors.sku && (
                <p className="text-sm text-red-600">{errors.sku.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="title">Product Title</Label>
              <Input
                id="title"
                placeholder="e.g., Chocolate Chip Cookies"
                {...register("title")}
              />
              {errors.title && (
                <p className="text-sm text-red-600">{errors.title.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="ingredientsText">Ingredients</Label>
              <textarea
                id="ingredientsText"
                placeholder="e.g., Flour, sugar, butter, chocolate chips..."
                className="flex min-h-[80px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                {...register("ingredientsText")}
              />
              {errors.ingredientsText && (
                <p className="text-sm text-red-600">
                  {errors.ingredientsText.message}
                </p>
              )}
            </div>

            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <AlertCircle className="h-4 w-4 text-orange-600" />
                <Label>Allergens</Label>
              </div>
              <p className="text-sm text-slate-600">
                Select all allergens present in this product
              </p>
              <div className="grid grid-cols-2 gap-3 rounded-lg border p-4 bg-slate-50">
                {ALLERGENS.map((allergen) => (
                  <label
                    key={allergen.bit}
                    className="flex items-center gap-3 cursor-pointer rounded-md p-2 hover:bg-white transition-colors"
                  >
                    <input
                      type="checkbox"
                      checked={hasAllergen(allergenMask, allergen.bit)}
                      onChange={() => toggleAllergenBit(allergen.bit)}
                      className="h-4 w-4 rounded border-gray-300 text-orange-600 focus:ring-orange-500"
                    />
                    <span className="text-lg">{allergen.icon}</span>
                    <span className="text-sm font-medium">{allergen.name}</span>
                  </label>
                ))}
              </div>
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setDialogOpen(false)}
                disabled={submitting}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={submitting}>
                {submitting
                  ? editingProduct
                    ? "Updating..."
                    : "Creating..."
                  : editingProduct
                  ? "Update Product"
                  : "Create Product"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Product</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete{" "}
              <span className="font-semibold">{deletingProduct?.title}</span>? This
              action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setDeleteDialogOpen(false)}
              disabled={submitting}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDelete}
              disabled={submitting}
            >
              {submitting ? "Deleting..." : "Delete Product"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
