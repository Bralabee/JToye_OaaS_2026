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
import { Store, Plus, Pencil, Trash2, MapPin, Calendar } from "lucide-react"
import type { Shop } from "@/types/api"
import { formatDistanceToNow } from "date-fns"

const shopSchema = z.object({
  name: z.string().min(1, "Name is required").max(100, "Name too long"),
  address: z.string().min(1, "Address is required").max(255, "Address too long"),
})

type ShopFormData = z.infer<typeof shopSchema>

export default function ShopsPage() {
  const [shops, setShops] = useState<Shop[]>([])
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [editingShop, setEditingShop] = useState<Shop | null>(null)
  const [deletingShop, setDeletingShop] = useState<Shop | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const { toast } = useToast()

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setValue,
  } = useForm<ShopFormData>({
    resolver: zodResolver(shopSchema),
  })

  useEffect(() => {
    fetchShops()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const fetchShops = async () => {
    try {
      setLoading(true)
      const response = await apiClient.get("/shops?size=100&sort=createdAt,desc")
      setShops(response.data.content || [])
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to load shops"
      toast({
        variant: "destructive",
        title: "Error loading shops",
        description: errorMessage,
      })
    } finally {
      setLoading(false)
    }
  }

  const openCreateDialog = () => {
    setEditingShop(null)
    reset({ name: "", address: "" })
    setDialogOpen(true)
  }

  const openEditDialog = (shop: Shop) => {
    setEditingShop(shop)
    setValue("name", shop.name)
    setValue("address", shop.address)
    setDialogOpen(true)
  }

  const openDeleteDialog = (shop: Shop) => {
    setDeletingShop(shop)
    setDeleteDialogOpen(true)
  }

  const onSubmit = async (data: ShopFormData) => {
    try {
      setSubmitting(true)

      if (editingShop) {
        // Update existing shop
        await apiClient.put(`/shops/${editingShop.id}`, data)
        toast({
          title: "Shop updated",
          description: `${data.name} has been updated successfully.`,
        })
      } else {
        // Create new shop
        await apiClient.post("/shops", data)
        toast({
          title: "Shop created",
          description: `${data.name} has been created successfully.`,
        })
      }

      setDialogOpen(false)
      reset()
      fetchShops()
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : `Failed to ${editingShop ? "update" : "create"} shop`
      toast({
        variant: "destructive",
        title: editingShop ? "Error updating shop" : "Error creating shop",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async () => {
    if (!deletingShop) return

    try {
      setSubmitting(true)
      await apiClient.delete(`/shops/${deletingShop.id}`)
      toast({
        title: "Shop deleted",
        description: `${deletingShop.name} has been deleted successfully.`,
      })
      setDeleteDialogOpen(false)
      setDeletingShop(null)
      fetchShops()
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to delete shop"
      toast({
        variant: "destructive",
        title: "Error deleting shop",
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
          <h1 className="text-4xl font-bold text-slate-900">Shops</h1>
          <p className="mt-2 text-slate-600">Manage your shop locations</p>
        </div>
        <Button onClick={openCreateDialog} className="gap-2">
          <Plus className="h-4 w-4" />
          Add Shop
        </Button>
      </motion.div>

      {/* Shops Table */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
      >
        <Card>
          <CardHeader>
            <CardTitle>All Shops</CardTitle>
            <CardDescription>
              {shops.length} shop{shops.length !== 1 ? "s" : ""} in total
            </CardDescription>
          </CardHeader>
          <CardContent>
            {shops.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <Store className="mb-4 h-12 w-12 text-slate-300" />
                <h3 className="mb-2 text-lg font-semibold text-slate-900">
                  No shops yet
                </h3>
                <p className="mb-4 text-sm text-slate-500">
                  Get started by creating your first shop
                </p>
                <Button onClick={openCreateDialog} variant="outline">
                  <Plus className="mr-2 h-4 w-4" />
                  Add Shop
                </Button>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Name</TableHead>
                      <TableHead>Address</TableHead>
                      <TableHead>Created</TableHead>
                      <TableHead className="text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {shops.map((shop) => (
                      <motion.tr
                        key={shop.id}
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        className="group"
                      >
                        <TableCell className="font-medium">
                          <div className="flex items-center gap-2">
                            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-100 text-blue-600">
                              <Store className="h-4 w-4" />
                            </div>
                            {shop.name}
                          </div>
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center gap-2 text-slate-600">
                            <MapPin className="h-4 w-4" />
                            {shop.address}
                          </div>
                        </TableCell>
                        <TableCell className="text-slate-600">
                          <div className="flex items-center gap-2">
                            <Calendar className="h-4 w-4" />
                            {formatDistanceToNow(new Date(shop.createdAt), {
                              addSuffix: true,
                            })}
                          </div>
                        </TableCell>
                        <TableCell className="text-right">
                          <div className="flex justify-end gap-2">
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => openEditDialog(shop)}
                              className="h-8 w-8 p-0"
                            >
                              <Pencil className="h-4 w-4" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => openDeleteDialog(shop)}
                              className="h-8 w-8 p-0 text-red-600 hover:bg-red-50 hover:text-red-700"
                            >
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </div>
                        </TableCell>
                      </motion.tr>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
          </CardContent>
        </Card>
      </motion.div>

      {/* Create/Edit Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {editingShop ? "Edit Shop" : "Create New Shop"}
            </DialogTitle>
            <DialogDescription>
              {editingShop
                ? "Update the shop details below."
                : "Add a new shop to your system."}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="name">Shop Name</Label>
              <Input
                id="name"
                placeholder="e.g., Main Street Location"
                {...register("name")}
              />
              {errors.name && (
                <p className="text-sm text-red-600">{errors.name.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="address">Address</Label>
              <Input
                id="address"
                placeholder="e.g., 123 Main St, London, UK"
                {...register("address")}
              />
              {errors.address && (
                <p className="text-sm text-red-600">{errors.address.message}</p>
              )}
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
                  ? editingShop
                    ? "Updating..."
                    : "Creating..."
                  : editingShop
                  ? "Update Shop"
                  : "Create Shop"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Shop</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete{" "}
              <span className="font-semibold">{deletingShop?.name}</span>? This
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
              {submitting ? "Deleting..." : "Delete Shop"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
